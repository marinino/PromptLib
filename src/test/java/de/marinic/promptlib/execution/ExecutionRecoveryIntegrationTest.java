package de.marinic.promptlib.execution;

import static org.assertj.core.api.Assertions.assertThat;

import de.marinic.promptlib.TestcontainersConfiguration;
import de.marinic.promptlib.prompt.PromptService;
import de.marinic.promptlib.prompt.PromptVersion;
import de.marinic.promptlib.prompt.PromptVersionRepository;
import de.marinic.promptlib.prompt.dto.CreatePromptRequest;
import de.marinic.promptlib.user.TestUsers;
import de.marinic.promptlib.user.UserRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression test: executions left PENDING or RUNNING by a crash or restart used to stay in that
 * status forever, since the executor queue only lives in memory. The rows here are written
 * straight through the repository - exactly what a previous run would have left behind, without
 * going through ExecutionService's AFTER_COMMIT hand-off.
 *
 * <p>Not @Transactional, and cleaned up manually - same reasons as ExecutionIntegrationTest.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ExecutionRecoveryIntegrationTest {

    @Autowired private ExecutionRecovery executionRecovery;
    @Autowired private ExecutionRepository executionRepository;
    @Autowired private PromptService promptService;
    @Autowired private PromptVersionRepository promptVersionRepository;
    @Autowired private CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private UUID userId;
    private UUID promptId;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry.circuitBreaker("llm").reset();
        userId = TestUsers.create(userRepository, passwordEncoder).getId();
        promptId =
                promptService
                        .create(new CreatePromptRequest("Recovery Test", null, "Hallo", Set.of(), null), userId)
                        .id();
    }

    @AfterEach
    void cleanUp() {
        // Deleting the prompt removes its versions (JPA cascade) and their executions (ON DELETE CASCADE).
        promptService.delete(promptId, userId);
    }

    @Test
    void leftoverExecutionsAreRecoveredAndNewerOnesAreLeftAlone() {
        PromptVersion version = promptVersionRepository.findByPromptIdAndVersionNo(promptId, 1).orElseThrow();

        Execution interrupted = executionRepository.save(leftover(version, ExecutionStatus.RUNNING));
        Execution neverStarted = executionRepository.save(leftover(version, ExecutionStatus.PENDING));
        Instant cutoff = Instant.now();
        Execution newerThanCutoff = executionRepository.save(leftover(version, ExecutionStatus.PENDING));

        executionRecovery.recover(cutoff);

        Execution failed = executionRepository.findById(interrupted.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("restart");
        assertThat(failed.getFinishedAt()).isNotNull();

        Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(
                        () -> assertThat(executionRepository.findById(neverStarted.getId()).orElseThrow().getStatus())
                                .isEqualTo(ExecutionStatus.SUCCEEDED));

        assertThat(executionRepository.findById(newerThanCutoff.getId()).orElseThrow().getStatus())
                .isEqualTo(ExecutionStatus.PENDING);
    }

    private static Execution leftover(PromptVersion version, ExecutionStatus status) {
        Execution execution = new Execution();
        execution.setPromptVersion(version);
        execution.setStatus(status);
        return execution;
    }
}
