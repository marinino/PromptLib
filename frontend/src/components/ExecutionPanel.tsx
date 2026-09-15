import { useCallback, useEffect, useRef, useState } from "react";
import { createExecution, getExecution, listExecutions } from "../api/endpoints";
import type { ExecutionResponse, VersionResponse } from "../api/types";
import { ErrorBox, Note, Panel, formatInstant, statusClass } from "./ui";

const POLL_INTERVAL_MS = 800;
// Muss laenger sein als das Backend im schlimmsten Fall braucht, bis eine Execution terminal
// wird: mit dem echten Client 3 Versuche x read-timeout 15 s + 0,2 s + 0,4 s Backoff ~ 46 s,
// dazu Wartezeit in der Executor-Queue. Frueher waren es 40 Polls = 32 s - das UI hoerte
// still auf, waehrend die Execution noch RUNNING war.
const MAX_POLL_DURATION_MS = 60_000;
const MAX_POLLS = Math.ceil(MAX_POLL_DURATION_MS / POLL_INTERVAL_MS);
const TERMINAL = ["SUCCEEDED", "FAILED"];

export function ExecutionPanel({
  promptId,
  versions,
}: {
  promptId: string;
  versions: VersionResponse[];
}) {
  const [versionNo, setVersionNo] = useState<number | null>(null);
  const [model, setModel] = useState("gpt-4o-mini");
  const [inputParams, setInputParams] = useState("");
  const [executions, setExecutions] = useState<ExecutionResponse[]>([]);
  const [watched, setWatched] = useState<ExecutionResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [pollGaveUp, setPollGaveUp] = useState(false);

  const cancelled = useRef(false);
  useEffect(() => {
    cancelled.current = false;
    return () => {
      cancelled.current = true;
    };
  }, []);

  const effectiveVersionNo = versionNo ?? versions[0]?.versionNo ?? null;

  const reload = useCallback(async () => {
    try {
      setExecutions(await listExecutions(promptId));
    } catch (e) {
      setError(e);
    }
  }, [promptId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  async function start() {
    if (effectiveVersionNo === null) {
      return;
    }
    let parsed: Record<string, unknown> | null = null;
    if (inputParams.trim().length > 0) {
      try {
        parsed = JSON.parse(inputParams) as Record<string, unknown>;
      } catch {
        setError(new Error("inputParams ist kein gueltiges JSON"));
        return;
      }
    }

    setBusy(true);
    setError(null);
    setPollGaveUp(false);
    try {
      // 202 Accepted: die Zeile existiert, die Arbeit hat noch nicht begonnen.
      const accepted = await createExecution({
        promptId,
        versionNo: effectiveVersionNo,
        model: model === "" ? null : model,
        inputParams: parsed,
      });
      setWatched(accepted);
      const finished = await poll(accepted.id);
      if (!finished && !cancelled.current) {
        setPollGaveUp(true);
      }
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
      void reload();
    }
  }

  /**
   * Pollt GET /executions/{id}, bis der Status terminal ist - jeder Poll steht im Log.
   * Liefert true, wenn ein terminaler Status erreicht wurde; false bei Abbruch oder wenn
   * MAX_POLLS aufgebraucht ist, waehrend die Execution noch laeuft.
   */
  async function poll(executionId: string): Promise<boolean> {
    for (let attempt = 0; attempt < MAX_POLLS; attempt++) {
      await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
      if (cancelled.current) {
        return false;
      }
      const current = await getExecution(executionId);
      setWatched(current);
      if (TERMINAL.includes(current.status)) {
        return true;
      }
    }
    return false;
  }

  return (
    <Panel title="Ausfuehrungen" endpoint="POST /api/v1/executions - 202 / GET ?promptId=">
      <div className="row">
        <label className="field">
          Version
          <select
            value={effectiveVersionNo ?? ""}
            onChange={(e) => setVersionNo(Number(e.target.value))}
          >
            {versions.map((v) => (
              <option key={v.versionNo} value={v.versionNo}>
                v{v.versionNo}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          model
          <input value={model} onChange={(e) => setModel(e.target.value)} />
        </label>
        <label className="field" style={{ flex: 1 }}>
          inputParams (JSON, optional)
          <input
            value={inputParams}
            onChange={(e) => setInputParams(e.target.value)}
            placeholder={JSON_PLACEHOLDER}
          />
        </label>
      </div>
      <div className="row" style={{ marginTop: 8 }}>
        <button
          className="primary"
          disabled={busy || effectiveVersionNo === null}
          onClick={() => void start()}
        >
          Ausfuehren
        </button>
        <button className="link" onClick={() => void reload()}>
          Liste neu laden
        </button>
      </div>
      <ErrorBox error={error} />

      {watched !== null && <StatusTimeline execution={watched} />}
      {pollGaveUp && (
        <div className="error">
          Polling nach {MAX_POLL_DURATION_MS / 1000} s beendet — die Ausfuehrung ist noch nicht
          abgeschlossen und laeuft im Backend weiter. Spaeter "Liste neu laden", um das Ergebnis
          zu sehen.
        </div>
      )}

      <Note>
        <b>Der Request wartet nicht auf das LLM.</b> <code>POST</code> antwortet sofort mit{" "}
        <code>202 Accepted</code>, einem <code>Location</code>-Header und Status{" "}
        <code>PENDING</code> — dieses UI pollt danach{" "}
        <code>GET /api/v1/executions/{"{id}"}</code>. Im Request-Log rechts ist die Abfolge
        sichtbar: erst 202, dann mehrere 200 mit wechselndem Status.
      </Note>
      <Note>
        <b>Zwei Fallen, die dahinter stecken.</b> Der Async-Thread startet ueber ein{" "}
        <code>@TransactionalEventListener(AFTER_COMMIT)</code> — wuerde er direkt aus der
        schreibenden Transaktion heraus starten, koennte er die Zeile lesen wollen, bevor sie
        ueberhaupt committed ist. Und der LLM-Call laeuft komplett ohne offene Transaktion; jedes{" "}
        <code>find</code>/<code>save</code> im <code>ExecutionRunner</code> ist seine eigene kurze
        Transaktion, damit kein Connection-Pool-Platz sekundenlang blockiert wird.
      </Note>
      <Note>
        <b>Ein Fehler bleibt nie unsichtbar.</b> Bei einer <code>@Async void</code>-Methode wartet
        niemand mehr auf das Ergebnis — eine entkommende Exception wuerde nur geloggt und
        verworfen, die Zeile bliebe fuer immer auf <code>RUNNING</code> ohne{" "}
        <code>finishedAt</code>. Deshalb faengt <code>ExecutionRunner</code> jede{" "}
        <code>Exception</code> und schreibt in jedem Fall <code>FAILED</code> samt{" "}
        <code>errorMessage</code>. Mit einer <code>__FAIL__</code>- oder{" "}
        <code>__BUG__</code>-Version oben laesst sich das hier ausloesen.
      </Note>
      <Note>
        <b>Resilience4j sitzt um den LLM-Aufruf.</b> <code>@Retry</code> aussen (3 Versuche,
        exponentiell), <code>@CircuitBreaker</code> innen — jeder Retry-Versuch ist selbst ein
        Breaker-Aufruf. Zum Nachsehen: eine <code>__FAIL__</code>-Version zweimal hintereinander
        ausfuehren. Beim ersten Mal steht <em>Mock LLM client simulated a failure</em> in der
        Zeile (Retry erschoepft), beim zweiten Mal kippt der Breaker und die{" "}
        <code>errorMessage</code> wechselt zu <em>LLM temporarily unavailable (circuit breaker
        open)</em> — ab da wird gar nicht mehr versucht, bis er sich nach{" "}
        <code>wait-duration-in-open-state</code> selbst wieder auf <code>HALF_OPEN</code> stellt.
      </Note>

      <table style={{ marginTop: 12 }}>
        <thead>
          <tr>
            <th>Status</th>
            <th>Version</th>
            <th>Modell</th>
            <th>Ausgabe / Fehler</th>
            <th>Tokens</th>
            <th>Latenz</th>
            <th>fertig</th>
          </tr>
        </thead>
        <tbody>
          {executions.map((execution) => (
            <tr key={execution.id}>
              <td>
                <span className={`badge ${statusClass(execution.status)}`}>{execution.status}</span>
              </td>
              <td className="mono">v{execution.versionNo}</td>
              <td className="mono muted">{execution.model ?? "—"}</td>
              <td className="mono" style={{ maxWidth: 340, whiteSpace: "pre-wrap" }}>
                {execution.errorMessage !== null ? (
                  <span className="error">{execution.errorMessage}</span>
                ) : (
                  (execution.output ?? "—")
                )}
              </td>
              <td className="mono muted">
                {execution.tokensIn ?? "—"} / {execution.tokensOut ?? "—"}
              </td>
              <td className="mono muted">
                {execution.latencyMs === null ? "—" : `${execution.latencyMs} ms`}
              </td>
              <td className="small muted">{formatInstant(execution.finishedAt)}</td>
            </tr>
          ))}
          {executions.length === 0 && (
            <tr>
              <td colSpan={7} className="muted small">
                Noch keine Ausfuehrungen.
              </td>
            </tr>
          )}
        </tbody>
      </table>

      <Note>
        <b>Auch diese Liste ist zugriffsgeschuetzt.</b> <code>GET /executions</code> und{" "}
        <code>GET /executions/{"{id}"}</code> pruefen den dahinterliegenden Prompt ueber dieselbe{" "}
        <code>PromptAccess</code>-Regel wie die Prompt-Endpoints — sonst waere der Inhalt eines
        privaten Prompts ueber seine Ausfuehrungsausgabe lesbar gewesen.
      </Note>
    </Panel>
  );
}

const JSON_PLACEHOLDER = '{"sprache": "de"}';

function StatusTimeline({ execution }: { execution: ExecutionResponse }) {
  const stages = ["PENDING", "RUNNING", execution.status === "FAILED" ? "FAILED" : "SUCCEEDED"];
  const reachedIndex = stages.indexOf(execution.status);

  return (
    <div className="row" style={{ marginTop: 10 }}>
      {stages.map((stage, index) => (
        <span
          key={stage}
          className={`badge ${index <= reachedIndex ? statusClass(stage) : ""}`}
          style={index <= reachedIndex ? undefined : { opacity: 0.4 }}
        >
          {stage}
        </span>
      ))}
      <span className="muted small mono">{execution.id}</span>
    </div>
  );
}
