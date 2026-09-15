package de.marinic.promptlib.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<Execution, UUID> {

    List<Execution> findByPromptVersion_Prompt_IdOrderByCreatedAtDesc(UUID promptId);

    List<Execution> findByStatusAndCreatedAtBefore(ExecutionStatus status, Instant createdBefore);
}
