package de.marinic.promptlib.execution;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * Cleans up executions a previous run of the application left behind. The executor queue lives
 * only in memory: after a crash or restart, nothing is ever going to pick up a PENDING row again,
 * and a RUNNING row's thread is simply gone - both would otherwise stay in that status forever.
 *
 * <ul>
 *   <li>PENDING never started, so it is safe to queue it again.
 *   <li>RUNNING may already have called the LLM (and been billed for it) - running it a second
 *       time could cost twice, so it is marked FAILED instead and the user can start it again.
 * </ul>
 *
 * <p>Assumes a single application instance. With several instances behind a load balancer, a
 * restarting instance would also "recover" executions that another, healthy instance is
 * running right now. That would need a claim/lease per execution instead.
 *
 * <p>Deliberately not @Transactional, same reasoning as {@link ExecutionRunner}: each save is its
 * own committed transaction, so a PENDING row handed to the async runner is already visible to
 * it - no after-commit hand-off needed, the rows were committed by the previous run anyway.
 */
@Component
public class ExecutionRecovery {

    private static final Logger log = LoggerFactory.getLogger(ExecutionRecovery.class);

    private final ExecutionRepository executionRepository;
    private final ExecutionRunner executionRunner;

    // Captured when this bean is created, i.e. before the web server accepts its first request.
    // Only rows older than this belong to a previous run - an execution created by a request that
    // arrives before ApplicationReadyEvent fires is already on its way through the normal
    // AFTER_COMMIT path and must not be queued a second time.
    private final Instant startedAt = Instant.now();

    public ExecutionRecovery(ExecutionRepository executionRepository, ExecutionRunner executionRunner) {
        this.executionRepository = executionRepository;
        this.executionRunner = executionRunner;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        recover(startedAt);
    }

    /** Package-private so tests can pass a cutoff after inserting their own leftover rows. */
    void recover(Instant createdBefore) {
        List<Execution> interrupted =
                executionRepository.findByStatusAndCreatedAtBefore(ExecutionStatus.RUNNING, createdBefore);
        for (Execution execution : interrupted) {
            markFailed(execution, "Interrupted by an application restart - start it again");
        }

        List<Execution> neverStarted =
                executionRepository.findByStatusAndCreatedAtBefore(ExecutionStatus.PENDING, createdBefore);
        int requeued = 0;
        for (Execution execution : neverStarted) {
            try {
                executionRunner.run(execution.getId());
                requeued++;
            } catch (TaskRejectedException e) {
                // Executor queue is full (see AsyncConfig) - better an honest FAILED than a row
                // that stays PENDING until the next restart.
                markFailed(execution, "Could not be queued again after an application restart - start it again");
            }
        }

        if (!interrupted.isEmpty() || !neverStarted.isEmpty()) {
            log.warn(
                    "Recovered executions from a previous run: {} RUNNING marked FAILED, {} of {} PENDING queued again",
                    interrupted.size(),
                    requeued,
                    neverStarted.size());
        }
    }

    private void markFailed(Execution execution, String message) {
        execution.setStatus(ExecutionStatus.FAILED);
        execution.setErrorMessage(message);
        execution.setFinishedAt(Instant.now());
        executionRepository.save(execution);
    }
}
