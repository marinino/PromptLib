# PromptLib

Backend-Übungsprojekt zum Auffrischen von Java, Spring Boot und Backend-Grundlagen.
Verwaltung, Versionierung und Suche von Prompts über eine REST-API.

# Hinweis zur Entstehung
Ein Lernprojekt zum Auffrischen von Java und Spring Boot. Bei der Umsetzung habe ich KI-Werkzeuge eingesetzt — für Recherche, als Sparringspartner bei Entwurfsentscheidungen und für Boilerplate. Die unten dokumentierten Entscheidungen und Trade-offs habe ich jeweils selbst nachvollzogen und, wo es um konkretes Verhalten ging, live überprüft statt sie zu übernehmen.

Der vollständige Feature- und Umsetzungsplan steht in [promptlib-featureplan.md](promptlib-featureplan.md).

## Setup

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

`GET /actuator/health` sollte danach `{"status":"UP"}` liefern.

Das Profil `dev` liefert ein JWT-Secret für die lokale Entwicklung. Ohne Profil startet die App
nur, wenn die Umgebungsvariable `JWT_SECRET` gesetzt ist (mindestens 32 Zeichen). Ein Default in
`application.properties` gibt es bewusst nicht mehr: er stand öffentlich im Repo, und eine
vergessene Variable beim Deployment wäre niemandem aufgefallen, während jeder mit dem Default
gültige Tokens für beliebige Nutzer hätte signieren können.

Manuelle Requests: [requests/prompts.http](requests/prompts.http).

Optional das Demo-Frontend dazu (siehe [Frontend](#frontend-stufe-5)):

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

## Architektur

```
Controller → Service → Repository (+ Specification) → Entity → PostgreSQL
```

- **Controller** kennt nur HTTP (Status-Codes, Query-Parameter), arbeitet ausschließlich mit DTOs
- **Service** kapselt Fachlogik und Transaktionsgrenzen, mappt Entities → DTOs *innerhalb* der Transaktion
  (wegen `spring.jpa.open-in-view=false` — sonst `LazyInitializationException`)
- **Repository** kennt nur Datenzugriff (`JpaRepository`, `JpaSpecificationExecutor`)

## Frontend (Stufe 5)

`frontend/` enthält ein bewusst schmuckloses React-plus-Vite-UI (`npm --prefix frontend run dev`,
danach [localhost:5173](http://localhost:5173)). Es ist **kein** eigenständiges Produkt, sondern
eine Bedienoberfläche für die API: Jedes Bedienelement gehört zu genau einem Endpoint, und neben
jedem steht ein kurzer Kasten, der erklärt, welche Backend-Entscheidung dahintersteckt — Ownership
und Sichtbarkeit, Pessimistic Locking bei der Versionsnummer, `202 Accepted` plus Polling, das
Catch-all-Netz im `ExecutionRunner`, Retry und Circuit Breaker.

Zwei Dinge machen die API dabei sichtbar, die eine normale Oberfläche gerade versteckt:

- **Ein Request-Log** in der Seitenspalte protokolliert jeden Aufruf mit Methode, Pfad,
  Statuscode, Dauer und beiden JSON-Bodies. Damit sind Details prüfbar statt behauptet: das
  `202` beim Start einer Execution gefolgt von `200`-Polls mit wechselndem Status, der
  partielle `PATCH`-Body beim Umschalten der Sichtbarkeit, das `404` (nicht `403`) bei einem
  fremden privaten Prompt, die Feldfehler-Liste im `ProblemDetail` bei Validierungsfehlern.
- **Ein Knopf „Request ohne Token senden"** provoziert absichtlich ein `401` aus der
  Security-Filterkette — die Stelle, an der Spring Securitys Default einen leeren Body geliefert
  hätte und `ProblemDetailSecurityHandlers` jetzt dasselbe JSON schreibt wie jeder andere Fehler.

Ein kleines Actuator-Panel liest zusätzlich `executor.active`/`executor.completed` (getaggt mit
`executionTaskExecutor`) und `hikaricp.connections.active`/`.idle`. Damit ist die Aussage aus
Stufe 3 nachprüfbar statt behauptet: Während einer Ausführung arbeitet ein Executor-Thread,
während die aktiven DB-Connections bei null bleiben — es ist eben keine Transaktion offen,
solange der LLM-Call läuft. Der Circuit-Breaker-Zustand ließe sich genauso anzeigen, aber
Resilience4j registriert seine Metriken hier nicht in der Micrometer-Registry (dafür fehlt
`resilience4j-micrometer`); im UI wird er deshalb über die `errorMessage` einer fehlgeschlagenen
Execution sichtbar gemacht statt über eine Metrik, die es nicht gibt.

**Am Backend wurde dafür keine Zeile geändert.** Der Vite-Dev-Server proxyt `/api` und `/actuator`
auf Port 8080, damit läuft alles unter derselben Origin und es braucht keine CORS-Konfiguration,
die es nur wegen dieses Demo-UIs gäbe. Abhängigkeiten sind nur React, Vite und TypeScript — kein
UI-Framework, kein Router, keine State-Bibliothek, kein CSS-Framework, damit der Ordner klein
bleibt und der Schwerpunkt des Projekts erkennbar auf dem Backend liegt. Die TypeScript-Typen in
`frontend/src/api/types.ts` sind absichtlich von Hand geschrieben statt aus einer OpenAPI-Spec
generiert: so steht der konsumierte Vertrag sichtbar im Repo, statt in einem Generatorlauf zu
verschwinden.

## Entscheidungen und Trade-offs

### Race Condition bei der Versionsnummer-Vergabe

Beim Anlegen einer neuen `PromptVersion` muss die nächste freie `version_no` ermittelt werden.
Naiv (`max(version_no) + 1` im Service) ist das nicht race-safe: zwei parallele Requests können
denselben, veralteten Höchstwert lesen und beide dieselbe Nummer vergeben wollen.

Gelöst über **Pessimistic Locking** (`SELECT ... FOR UPDATE` auf der `Prompt`-Zeile, siehe
`PromptRepository.findByIdForUpdate`): der zweite Request wartet, bis der erste committed hat, und
berechnet danach auf Basis des aktuellen Stands. Ein `UNIQUE`-Constraint
(`uq_prompt_version_no` auf `(prompt_id, version_no)`) dient zusätzlich als Sicherheitsnetz.

Bewiesen durch `PromptVersionConcurrencyTest`: 10 echte parallele Requests gegen denselben Prompt
liefern lückenlose, eindeutige Versionsnummern.

### Suche: JPA Specifications statt Volltextsuche

Für `GET /api/v1/prompts?query=...&tags=...` wurden **Specifications** (statt Postgres-Volltextsuche
mit `tsvector`) gewählt, weil Specifications direkt auf Entity-Feldnamen arbeiten und sich dadurch
sauber mit Spring Data's `Pageable`/`Sort` kombinieren lassen. Eine native Volltextsuche-Query hätte
für die COUNT-Query und Sortierung zusätzliche manuelle Arbeit bedeutet.

### N+1 bei der Prompt-Suche

**Problem:** `GET /api/v1/prompts` lädt eine Seite von Prompts; jeder Prompt hat eine `@ManyToMany`-Beziehung
zu `Tag` (`FetchType.LAZY`). Beim Mapping zu `PromptResponse` (`prompt.getTags()`) löst das für **jeden**
Prompt auf der Seite eine eigene Lazy-Load-Query aus.

**Nachweis (Hibernate-Statistiken, 5 Prompts mit je 2 Tags, Persistence Context zwischen Anlegen und
Suche geleert, damit der First-Level-Cache das Problem nicht verdeckt):**

```
1x SELECT prompt        (Content-Query mit LIMIT/OFFSET)
1x SELECT count(id)     (Count-Query für Pagination)
5x SELECT ... prompt_tag JOIN tag WHERE prompt_id = ?   (eine pro Prompt!)
= 7 Statements für 5 Prompts  →  2 + n
```

**Erster Lösungsversuch (naiv):** ein `fetch("tags", LEFT)` direkt in der Specification, kombiniert mit
Pagination. Klassisch bekannt als Auslöser der Hibernate-Warnung `HHH90003004`
("firstResult/maxResults specified with collection fetch; applying in memory") — Hibernate kann bei
einem Collection-Fetch-Join kein SQL-`LIMIT`/`OFFSET` mehr anwenden (der Join vervielfacht Zeilen pro
Tag) und paginiert deshalb im Speicher, was bei größeren Datenmengen die gesamte Tabelle lädt.

**Tatsächliches Ergebnis mit Hibernate 6 (Spring Boot 4.1.1):** Die Warnung trat **nicht** auf.
Hibernate generiert stattdessen eine Subquery, die zuerst paginiert (`LIMIT`/`OFFSET` auf den reinen
Prompt-IDs) und erst danach die Tags an die bereits eingeschränkte Ergebnismenge joint:

```sql
select p1_0.id, ..., t1_1.name, ...
from (
    select distinct p1_0.id, ... from prompt p1_0
    order by p1_0.updated_at desc
    offset ? rows fetch first ? rows only
) p1_0
left join prompt_tag t1_0 on p1_0.id = t1_0.prompt_id
left join tag t1_1 on t1_1.id = t1_0.tag_id
```

`HHH90003004` ist demnach ein **historisches** Problem älterer Hibernate-Versionen (5.x), das in
Hibernate 6 durch diese Subquery-Strategie gelöst wurde — ein gutes Beispiel dafür, dass
Framework-Fallstricke aus Tutorials/Blogposts nicht automatisch für die aktuell verwendete Version
gelten.

**Finale Lösung:** `PromptSpecifications.fetchTags()` — ein `LEFT JOIN FETCH` auf `tags`, das nur bei der
Content-Query greift (nicht bei der COUNT-Query, wo ein Fetch semantisch ungültig wäre; das wird über
`cq.getResultType() != Long.class` geprüft). Immer als Teil der Suche aktiv.

**Ergebnis:** konstant **2 Statements**, unabhängig von der Anzahl der Prompts auf der Seite
(bewiesen in `PromptSearchQueryCountTest`, Hibernate-Statistiken via `SessionFactory.getStatistics()`).

### Race Condition beim Tag-Anlegen

`resolveTags()` (Prompt anlegen/ändern) legt neue Tags per Find-or-Create an. Der ursprüngliche
Ansatz (`findByName().orElseGet(() -> save(...))`) hatte dieselbe Art von Lücke wie oben: zwei
parallele Requests, die dasselbe neue Tag zum ersten Mal anlegen, konnten beide `save()` versuchen —
einer scheiterte mit `DataIntegrityViolationException` auf dem `UNIQUE`-Constraint.

Ein einfaches `try/catch` um den `save()` reicht hier nicht zuverlässig: schlägt ein `flush()` mit
einer Constraint-Verletzung fehl, markiert Hibernate die aktuelle Transaktion oft als beschädigt —
weitere Arbeit in *derselben* Transaktion kann danach unvorhersehbar scheitern, selbst wenn die
Exception im Code gefangen wird. Der "sichere" Weg wäre eine separate Transaktion
(`@Transactional(REQUIRES_NEW)`) nur fürs Tag-Anlegen gewesen — deutlich mehr Komplexität.

**Lösung:** `TagRepository.upsertByName()` — `INSERT ... ON CONFLICT (name) DO NOTHING` (natives SQL).
Wirft nie, egal wie viele Requests gleichzeitig dasselbe neue Tag anlegen wollen; Postgres serialisiert
das intern über eine Sperre auf dem Unique-Index. Danach garantiert ein `findByName()`, dass die Zeile
existiert — unabhängig davon, wer sie tatsächlich eingefügt hat.

Verifiziert in beide Richtungen in `PromptTagConcurrencyTest`: 10 parallele Prompt-Erstellungen mit
demselben neuen Tag laufen mit dem Fix durch; gegen den alten Code (kurz zurückgeschaltet, um zu
prüfen, dass der Test wirklich etwas beweist) schlägt derselbe Test zuverlässig fehl.

### Stufe 3: Async-Ausführung ohne offene Transaktion während des LLM-Calls

`POST /api/v1/executions` legt eine `Execution`-Zeile mit Status `PENDING` an und gibt sofort `202
Accepted` zurück; der eigentliche LLM-Call läuft danach asynchron. Zwei Fallstricke dabei, die der
naive Ansatz ("Async-Methode direkt aus der Transaktion heraus aufrufen") beide hätte:

1. **Die Transaktion darf nicht offen bleiben, während der LLM-Call läuft** — ein Request an eine
   externe API kann Sekunden dauern; eine offene DB-Transaktion so lange zu halten, blockiert
   Connections aus dem Pool unnötig lange.
2. **Der Async-Thread darf nicht starten, bevor die erzeugende Transaktion committed hat** — würde
   `ExecutionService.create()` den Async-Aufruf direkt (noch innerhalb der eigenen `@Transactional`-
   Methode) auslösen, könnte der neue Thread versuchen, die gerade erst eingefügte `Execution`-Zeile
   zu lesen, *bevor* sie überhaupt committed und damit für andere Transaktionen sichtbar ist.

**Lösung:**
- `ExecutionService.create()` speichert die `Execution`-Zeile in einer kurzen Transaktion und
  publiziert ein `ExecutionCreatedEvent` — Events werden aber erst zugestellt, wenn `create()`
  zurückkehrt.
- `ExecutionCreatedListener` reagiert per `@TransactionalEventListener(phase = AFTER_COMMIT)` — feuert
  garantiert erst, *nachdem* die erzeugende Transaktion erfolgreich committed hat.
- Erst dann startet `ExecutionRunner.run()` auf einem eigenen `TaskExecutor`. Jeder Repository-Aufruf
  darin (`findById`/`save`) ist seine eigene kurze Transaktion; der LLM-Call selbst läuft dazwischen
  komplett ohne offene Transaktion.

`ExecutionRunner` ist eine eigene Klasse (statt einer Methode auf `ExecutionService`) — aber *nicht*
wegen der `@Async`-Selbstaufruf-Falle: `ExecutionCreatedListener` ruft `run()` ohnehin über eine
injizierte Fremd-Bean-Referenz auf, kein `this.`-Aufruf, also hätte `@Async` so oder so funktioniert
(live mit Thread-Namen-Logging nachgewiesen: `create()`/`onExecutionCreated()` laufen synchron auf
`main`, erst `run()` wechselt auf `execution-N` — einzig wegen `@Async` auf dieser Methode, unabhängig
von der Klassenzugehörigkeit). Der eigentliche Grund für die Trennung ist reine
Verantwortungstrennung: `ExecutionService` verwaltet Transaktionsgrenzen, `ExecutionRunner` die
Async-Arbeit ohne Transaktionsgrenze.

Bewiesen in `ExecutionIntegrationTest`: Erfolgs- und Fehlerfall laufen gegen die echte (Mock-)LLM-
Anbindung durch, mit `Awaitility` auf den finalen Status gepollt — kein Mocking der eigenen Klassen,
weil genau das Zusammenspiel zwischen ihnen geprüft werden soll.

### Verschluckte Exceptions bei `@Async void`-Methoden

`ExecutionRunner.run()` fing ursprünglich nur `LlmException` ab (den kontrollierten Fehlerfall). Ein
echter, unerwarteter Bug im Code (nicht die LLM-API selbst) hätte diesen `catch`-Block übersprungen —
mit einem gravierenden Effekt: Bei einer `@Async void`-Methode gibt es **keinen Aufrufer, der noch auf
das Ergebnis wartet** (der Request ist ja längst mit `202 Accepted` beantwortet). Eine Exception, die
aus so einer Methode entkommt, landet nur bei Spring's `SimpleAsyncUncaughtExceptionHandler`, der sie
loggt und dann verwirft — die `Execution`-Zeile bleibt für immer bei `RUNNING` stehen, ohne
`finishedAt`, ohne `errorMessage`, unsichtbar für jeden API-Konsumenten.

Live nachgestellt: ein `IllegalStateException` (bewusst *keine* `LlmException`) im Mock-Client
provoziert, Status blieb nach mehreren Sekunden bei `RUNNING`, `finishedAt: null`, einzige Spur ein
Log-Eintrag von Springs generischem Handler.

**Fix:** `run()` fängt jetzt jede `Exception` (nicht nur `LlmException`) und ruft in jedem Fall
`recordFailure(...)` auf — mit unterschiedlichem Log-Level: `WARN` ohne Stacktrace für erwartete
`LlmException`, `ERROR` mit vollem Stacktrace für alles andere (ein echter Bug verdient Aufmerksamkeit,
eine erwartete LLM-Fehlermeldung nicht). Regressionstest: `unexpectedBugIsStillRecordedAsFailed`.

### Stufe 3.5: Retry, Circuit Breaker, Timeouts (Resilience4j)

`LlmClient.complete()` (beide Implementierungen) trägt `@Retry(name = "llm")` +
`@CircuitBreaker(name = "llm")`. Für den "Fallback auf FAILED-Status" aus dem Plan brauchte es
keinen extra Resilience4j-`fallbackMethod` — der `ExecutionRunner`-Catch-all von oben übernimmt das
bereits automatisch, egal welche Exception am Ende durchkommt (`LlmException`,
`CallNotPermittedException` vom offenen Breaker, oder ein echter Bug).

**`spring-boot-starter-aop` heißt in Boot 4 `spring-boot-starter-aspectj`** — ohne dieses Umbenennen
zu kennen, schlägt die Dependency-Auflösung fehl (`resilience4j-spring-boot3` selbst ist offiziell
für Boot 3 gebaut, funktioniert aber unverändert auf Boot 4.1.1 — Kontext lädt sauber).

**Zusammenspiel Retry + Circuit Breaker live gemessen** (nicht angenommen), über einen Aufrufzähler
in `MockLlmClient` (`resilience4j.retry.instances.llm.max-attempts=3`,
`sliding-window-size=4`, `minimum-number-of-calls=4`, `failure-rate-threshold=50%`):

| Aufruf | Ergebnis | Tatsächliche `complete()`-Aufrufe |
|---|---|---|
| 1 | `LlmException` (Retry erschöpft) | 3 |
| 2 | `CallNotPermittedException` (Breaker klappt mitten im 1. Retry-Versuch auf) | 1 |
| 3+ | `CallNotPermittedException`, sofort | 0 |

Spring Boot's Default-Reihenfolge legt `@Retry` **außen**, `@CircuitBreaker` **innen** — jeder
Retry-Versuch ist selbst ein Circuit-Breaker-Aufruf. `CallNotPermittedException` steht nicht in
`retry-exceptions`, wird also nicht selbst nochmal retried, sondern fliegt direkt durch. Regressionstest:
`ResilienceTest.repeatedFailuresRetryThenTripTheCircuitBreaker`.

**Falle dabei:** Der Circuit Breaker ist Singleton-Zustand (`CLOSED`/`OPEN`/...), geteilt über den
kompletten Testlauf — genau wie die Datenbank bei den `@SpringBootTest`-Klassen weiter oben, nur
diesmal im Speicher statt in Postgres. Ohne expliziten Reset ließ ein zuerst laufender Test (Reihenfolge
nicht garantiert) den Breaker für alle danach `OPEN` zurück. Fix: `CircuitBreakerRegistry.circuitBreaker(
"llm").reset()` in `@BeforeEach` von `ResilienceTest` und `ExecutionIntegrationTest`.

**Echter Bug, gefunden durch tatsächliches Warten (nicht nur Doku gelesen):** `OPEN → HALF_OPEN` passiert
**nicht** automatisch, nur weil `wait-duration-in-open-state` verstrichen ist — `resilience4j`'s eigener
Default für `automatic-transition-from-open-to-half-open-enabled` ist `false`. Ohne diese Property
explizit auf `true` zu setzen, wäre unser Breaker nach dem ersten Auslösen für immer `OPEN` geblieben
(bis zum App-Neustart) — hätte jede weitere Execution permanent abgelehnt, selbst Stunden nachdem sich
die LLM-API längst wieder erholt hätte. Mit Zeitstempel-Logging live nachgestellt: nach 3s Warten bei
`false` immer noch `OPEN`; mit `automatic-transition-from-open-to-half-open-enabled=true` schaltet ein
Hintergrund-Task von sich aus auf `HALF_OPEN` um — sogar **während** man wartet, nicht erst beim
nächsten Aufruf. Regressionstest: `ResilienceTest.circuitBreakerSelfHealsAfterWaitDuration`.

**Timeouts:** `RealLlmClient` bekommt Connect-/Read-Timeout aus `LlmProperties` über einen
`SimpleClientHttpRequestFactory` auf dem `RestClient.Builder` — ohne das würde ein hängender Request
nie enden.

**Nicht umgesetzt:** Rate Limiting "pro Nutzer" braucht einen Nutzer-Begriff, den es erst mit Auth in
Stufe 4 gibt. Ein globaler Rate Limiter hätte die eigentliche Anforderung nicht erfüllt, deshalb bewusst
verschoben statt vorgetäuscht.

### Stufe 4.1 — Spring Security: Registrierung, Login, Ownership

**JWT statt Session:** Login/Registrierung geben ein signiertes JWT zurück (`AuthService`,
`JwtService`, HS256 über `io.jsonwebtoken:jjwt`), jeder weitere Request trägt es im
`Authorization: Bearer <token>`-Header. `SecurityConfig` schaltet Sessions komplett ab
(`SessionCreationPolicy.STATELESS`) und deaktiviert CSRF — CSRF schützt vor **ungewollten**
Requests, die der Browser bei Cookie-basierter Session-Auth automatisch mitschickt; das gibt es
hier nicht, der Client muss den Header selbst aktiv setzen.

**Zwei getrennte Prinzipal-Wege, bewusst:** `UserDetailsServiceImpl.loadUserByUsername(email)`
wird nur einmal gebraucht — beim Login, wenn `AuthenticationManager`/`DaoAuthenticationProvider`
Email+Passwort (BCrypt) prüfen. `JwtAuthenticationFilter` läuft dagegen auf **jedem** Request und
hat die User-ID schon direkt im Token (`sub`-Claim) — er lädt den `User` daher per ID über
`UserRepository`, nicht nochmal über `UserDetailsService`. Beide Wege landen im selben
`AppUserPrincipal` (implementiert `UserDetails`), aber "username" bedeutet in den beiden Fällen
etwas anderes (Email vs. ID) — deshalb zwei Pfade statt einem überladenen.

**Owner + Sichtbarkeit statt reiner Auth:** `prompt.owner_id` (FK auf `app_user`, nullable —
Prompts aus Stufe 1–3 haben keinen Owner) plus `visibility` (`PRIVATE`/`PUBLIC`,
`PromptAccess.requireReadable`/`requireOwner`, wiederverwendet von `PromptService`,
`PromptVersionService` **und** `ExecutionService`, obwohl die beiden letzteren nie
`PromptService` selbst aufrufen). Eine fehlende und eine fremde private Prompt-ID geben absichtlich
denselben 404 zurück — ein 403 würde verraten, dass unter dieser ID überhaupt etwas Privates
existiert. Nachweis (echter HTTP-Layer, zwei echte registrierte Nutzer, echtes Postgres):
`PromptOwnershipIntegrationTest.privatePromptIsHiddenFromOthersAndVisibleOnceMadePublic`.

Für **Schreibzugriffe** (`PATCH`/`DELETE` auf einen Prompt, neue Version anlegen, Version
aktivieren) gilt diese Tarnung bewusst **nicht**: `requireOwner` antwortet auch bei einem fremden
privaten Prompt mit `403` und verrät damit, dass die ID existiert. Das ist eine abgewogene
Entscheidung, keine Lücke — Prompt-IDs sind zufällige UUIDs und damit nicht enumerierbar, ein
Angreifer erfährt also nur etwas über eine ID, die er ohnehin schon besitzt. Im Gegenzug bekommt
jeder Schreibpfad eine ehrliche Antwort ("dir gehört das nicht") statt eines `404`, das auch bei
einer schlicht vertippten ID käme und gegen eine laufende API deutlich schwerer zu debuggen wäre.
`requireOwner` darf deshalb ausdrücklich **nicht** zusätzlich mit `requireReadable` abgesichert
werden — das würde den getesteten `403` still in einen `404` verwandeln.

**Registrierte E-Mails sind über `/register` erkennbar, bewusst:** Der Login antwortet für
"unbekannte E-Mail" und "falsches Passwort" absichtlich gleich (und Springs
`DaoAuthenticationProvider` rechnet bei unbekannter E-Mail sogar einen Dummy-BCrypt-Hash, damit
man es auch nicht an der Antwortzeit erkennt). `POST /auth/register` verrät es trotzdem: eine
bereits registrierte Adresse gibt `409`, eine neue `201`. Das echte Verstecken bräuchte eine
Registrierung mit Bestätigungsmail, die immer gleich antwortet ("Wir haben dir eine E-Mail
geschickt") — die gibt es hier nicht, und ohne sie würde ein echter Nutzer mit bestehendem Konto
nie erfahren, warum seine Registrierung nichts bewirkt. Der Login-Schutz verhindert also nur,
dass **dieser** Endpunkt die Information liefert, nicht, dass sie insgesamt verborgen bleibt.

**Stolperfalle beim `@WebMvcTest` der Controller:** Mit Spring Security auf dem Classpath wird
`JwtAuthenticationFilter` als `Filter`-Bean automatisch Teil jedes `@WebMvcTest`-Slices (Boot zählt
`Filter`-Implementierungen zu den slice-relevanten Typen) — sein Konstruktor braucht dann aber
`JwtService`/`UserRepository`, die dort sonst nicht existieren. Erster Versuch,
`addFilters = false` zu setzen, hat das zwar behoben, aber gleichzeitig
`@AuthenticationPrincipal` kaputt gemacht: der über `SecurityMockMvcRequestPostProcessors.user(...)`
gesetzte Principal erreicht `SecurityContextHolder` erst über `SecurityContextHolderFilter` — läuft
die echte Filterkette gar nicht erst, bleibt der Controller-Parameter `null`, und zwei Tests fielen
mit stillen Fehlbindungen statt sauberen Fehlern auf (einmal `NullPointerException`, einmal ein
Mockito-Aufruf mit `null` statt der erwarteten User-ID — je nachdem, ob der Controller-Code den
Principal direkt dereferenziert oder nur weiterreicht). Fix: `@Import(SecurityConfig.class)` plus
echte Filterkette (kein `addFilters = false` mehr), mit `JwtService`/`UserRepository`/
`UserDetailsServiceImpl` als `@MockitoBean`, da nie ein echtes Token durch die Tests geschickt wird.

**Bug gefunden und live verifiziert (nicht nur im Test):** Eine Ablehnung durch die
Security-Filterkette (fehlendes/ungültiges Token) erreicht nie einen Controller, also auch nie den
`@RestControllerAdvice` (`GlobalExceptionHandler`) — Spring Securitys Default dafür war ein
nacktes `response.sendError(401)`. Per `curl` gegen die echte, laufende App (nicht nur MockMvc,
das das Problem in einem ersten Testlauf sogar noch verschleiert hätte) bestätigt: `Content-Length:
0`, gar kein Body, während derselbe Statuscode aus einem Controller heraus (z. B. falsches
Passwort beim Login) sauberes `ProblemDetail`-JSON liefert — zwei Formate für denselben
Fehlerfall. Fix: `ProblemDetailSecurityHandlers` (`AuthenticationEntryPoint` +
`AccessDeniedHandler` in einer Klasse) schreibt jetzt manuell dasselbe `ProblemDetail`-JSON.
Nebenbei aufgefallen und mitgefixt: ohne explizites `response.setCharacterEncoding("UTF-8")`
vor dem Schreiben landet der Servlet-Container-Default (`ISO-8859-1`) im `Content-Type`-Header —
ebenfalls per `curl` verglichen mit dem Controller-Pfad, der implizit UTF-8 nutzt.
Regressionstest: `AuthFlowIntegrationTest.protectedEndpointWithoutTokenReturns401AsProblemDetail`.

**Nachgezogen (war zunächst bewusst ausgelassen):** `GET /executions/{id}` und `GET
/executions?promptId=` prüften anfangs gar nicht, ob der Aufrufer den zugrunde liegenden Prompt
überhaupt lesen darf — nur `POST /executions` tat das. `ExecutionService.get()` prüft jetzt über
`execution.getPromptVersion().getPrompt()` (derselbe Lazy-Proxy-Trick wie in `create()`),
`listByPrompt()` lädt dafür zusätzlich `PromptRepository` (nach demselben Muster wie
`PromptVersionService.list()`). Regressionstest (echtes HTTP, zwei echte Nutzer):
`ExecutionOwnershipIntegrationTest.executionsOfAPrivatePromptAreOnlyVisibleToItsOwner`.

### Nachträgliche Fixes aus einem Code-Review

**Client-Fehler kamen als `500` zurück.** Der Catch-all `@ExceptionHandler(Exception.class)` im
`GlobalExceptionHandler` fing auch Springs eigene Exceptions für Client-Fehler ab, bevor Spring
selbst den richtigen Status setzen konnte: ein HTML-Formular an einen JSON-Endpunkt (eigentlich
`415`), eine falsche HTTP-Methode (`405`), eine ID, die keine UUID ist (`400`) — alles wurde
`500 "An unexpected error occurred"` und als `ERROR` geloggt. Fix: Exceptions, die Springs
`ErrorResponse` implementieren, bringen Status, Header und `ProblemDetail` selbst mit und werden
jetzt durchgereicht. `MethodArgumentTypeMismatchException` gehört **nicht** dazu und hat einen
eigenen Handler. Regressionstests: `AuthControllerTest.loginWithFormBodyReturns415`,
`PromptControllerTest.unsupportedMethodReturns405` / `getWithNonUuidIdReturns400`.

**`PATCH` konnte Prompts ohne Titel erzeugen.** Beim Anlegen gilt `@NotBlank`, beim Ändern stand
nur `@Size(max = 200)` — und das erlaubt `""`. `@NotBlank` geht beim `PATCH` nicht, weil es auch
`null` ablehnt, und `null` heißt dort "nicht ändern". Fix: `@Pattern(regexp = "(?s).*\\S.*")` —
wie fast alle Bean-Validation-Annotationen ignoriert `@Pattern` `null` und prüft nur einen
tatsächlich gesendeten Wert.

**Tag-Liste verriet Tags fremder privater Prompts.** `GET /api/v1/tags` zählte über **alle**
Prompts. Tag-Namen sind Nutzereingaben, also sah jeder eingeloggte Nutzer die Tag-Namen (und
Anzahl) der privaten Prompts anderer. Ursache war strukturell: `TagController` rief das Repository
direkt auf und bekam den Aufrufer gar nicht erst mit — alle Zugriffsregeln leben aber in den
Services. Fix: `TagService`, und die Query zählt nur Prompts, die der Aufrufer sehen darf (dieselbe
Regel wie `PromptSpecifications.visibleTo`). Regressionstest:
`TagVisibilityIntegrationTest`.

**Race bei der Registrierung gab `500` statt `409`.** `existsByEmail()` und das Speichern sind
zwei Schritte — dieselbe Lücke wie ursprünglich beim Tag-Anlegen. Zwei gleichzeitige
Registrierungen kamen beide an der Prüfung vorbei, der Verlierer scheiterte am `UNIQUE`-Constraint
erst beim Commit, also außerhalb von `register()`. Fix: `saveAndFlush()` statt `save()`, damit der
`INSERT` noch innerhalb der Methode passiert und die `DataIntegrityViolationException` dort in
dieselbe `ConflictException` übersetzt werden kann. Regressionstest:
`UserRegistrationConcurrencyTest`.

**E-Mail-Normalisierung hing von der Server-Sprache ab.** `toLowerCase()` ohne Argument benutzt die
Default-Locale der JVM; unter Türkisch wird aus `I` ein `ı` ohne Punkt, `INFO@FIRMA.DE` also zu
`ınfo@fırma.de`. Außerdem stand die Normalisierung doppelt in `UserService` und `AuthService` —
ändert sich nur eine Seite, kommen bestehende Nutzer nicht mehr rein. Fix: eine gemeinsame
Methode `EmailAddresses.normalize()` mit `Locale.ROOT`. Regressionstest: `EmailAddressesTest`.

**Default-JWT-Secret.** Siehe [Setup](#setup): der öffentliche Default in `application.properties`
ist weg, `JwtService` bricht den Start ohne Secret mit einer klaren Meldung ab. Das Dev-Secret lebt
nur noch im Profil `dev`, die Tests bekommen ihres über `src/test/resources/config/
application.properties` — bewusst in `config/`, denn eine gleichnamige Datei direkt in
`src/test/resources` würde die Haupt-`application.properties` komplett **ersetzen** statt sie zu
ergänzen. Regressionstest: `JwtServiceTest`.

**Executions hingen nach einem Neustart für immer.** Die Executor-Queue lebt nur im Speicher. Nach
einem Absturz holt niemand eine `PENDING`-Zeile je wieder ab, und der Thread einer
`RUNNING`-Zeile ist schlicht weg. Fix: `ExecutionRecovery` beim `ApplicationReadyEvent` —
`PENDING` hat nie angefangen und wird erneut eingereiht, `RUNNING` wird `FAILED`, weil das LLM
eventuell schon aufgerufen (und bezahlt) wurde und ein zweiter Lauf doppelt kosten würde. Nur
Zeilen, die **vor** dem Start dieser Instanz angelegt wurden, sonst würde eine Execution, die ein
früher Request gerade regulär angestoßen hat, doppelt laufen. Setzt eine einzige Instanz voraus: mit
mehreren würde eine neu startende Instanz auch Executions "retten", die eine andere gerade
ausführt. Regressionstest: `ExecutionRecoveryIntegrationTest`.

**Frontend hörte still auf zu pollen.** 40 Polls à 800 ms = 32 s, das Backend braucht mit dem
echten Client im schlimmsten Fall aber 3 × 15 s Read-Timeout + 0,2 s + 0,4 s Backoff ≈ 46 s,
plus Wartezeit in der Queue. Das UI zeigte danach dauerhaft `RUNNING` ohne jeden Hinweis. Fix:
60 s Polling und eine sichtbare Meldung, wenn danach noch kein Endstatus erreicht ist.

## Tests

```bash
./mvnw test
```

Testcontainers startet dafür automatisch einen Postgres-Container über Docker — echte Datenbank statt
H2, weil die Migration Postgres-spezifisches SQL nutzt (`gen_random_uuid()`, `jsonb`), das H2 nicht
versteht.
