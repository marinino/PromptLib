package de.marinic.promptlib.user;

import static org.assertj.core.api.Assertions.assertThat;

import de.marinic.promptlib.TestcontainersConfiguration;
import de.marinic.promptlib.common.error.ConflictException;
import de.marinic.promptlib.user.dto.RegisterRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Regression test for the check-then-insert race in {@link UserService#register}: concurrent
 * registrations with the same email could all pass existsByEmail() before any of them inserted.
 * The losers then hit the UNIQUE constraint at commit time, outside register(), and came back as
 * a 500 instead of the 409 a sequential duplicate gets. Deliberately not @Transactional - each
 * thread needs its own, really committed transaction for the race to happen at all.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class UserRegistrationConcurrencyTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @Test
    void concurrentRegistrationsWithTheSameEmailYieldOneUserAndOnlyConflicts() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        int concurrentRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);

        List<Callable<User>> tasks =
                IntStream.range(0, concurrentRequests)
                        .<Callable<User>>mapToObj(
                                i -> () -> userService.register(new RegisterRequest(email, "password-" + i)))
                        .toList();

        List<Future<User>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        int succeeded = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<User> future : futures) {
            try {
                future.get();
                succeeded++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(failures).hasSize(concurrentRequests - 1).allMatch(ConflictException.class::isInstance);
        assertThat(userRepository.findByEmail(email)).isPresent();
    }
}
