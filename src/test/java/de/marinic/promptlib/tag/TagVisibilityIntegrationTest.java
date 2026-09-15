package de.marinic.promptlib.tag;

import static org.assertj.core.api.Assertions.assertThat;

import de.marinic.promptlib.TestcontainersConfiguration;
import de.marinic.promptlib.prompt.PromptService;
import de.marinic.promptlib.prompt.Visibility;
import de.marinic.promptlib.prompt.dto.CreatePromptRequest;
import de.marinic.promptlib.tag.dto.TagResponse;
import de.marinic.promptlib.user.TestUsers;
import de.marinic.promptlib.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Regression test: GET /api/v1/tags used to count over ALL prompts, so any logged-in user saw
 * the tag names - and number - of everyone else's private prompts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TagVisibilityIntegrationTest {

    @Autowired private PromptService promptService;
    @Autowired private TagService tagService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final List<UUID> createdPromptIds = new ArrayList<>();
    private UUID owner;

    // The test database is shared with every other @SpringBootTest class, and leftover prompts
    // would skew total-count assertions elsewhere (e.g. PromptSearchTest).
    @AfterEach
    void cleanUp() {
        createdPromptIds.forEach(id -> promptService.delete(id, owner));
        createdPromptIds.clear();
    }

    @Test
    void tagsOfOtherUsersPrivatePromptsAreNeitherListedNorCounted() {
        owner = TestUsers.create(userRepository, passwordEncoder).getId();
        UUID other = TestUsers.create(userRepository, passwordEncoder).getId();

        // Unique names: tag rows are global and never cleaned up.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String secretTag = "secret-" + suffix;
        String sharedTag = "shared-" + suffix;

        createdPromptIds.add(promptService.create(prompt(Set.of(secretTag, sharedTag), Visibility.PRIVATE), owner).id());
        createdPromptIds.add(promptService.create(prompt(Set.of(sharedTag), Visibility.PUBLIC), owner).id());

        // The owner sees both tags, and both of their prompts under the shared one.
        assertThat(tagService.list(owner))
                .filteredOn(tag -> tag.name().endsWith(suffix))
                .containsExactly(new TagResponse(secretTag, 1), new TagResponse(sharedTag, 2));

        // Someone else sees the shared tag only, counting only the public prompt.
        assertThat(tagService.list(other))
                .filteredOn(tag -> tag.name().endsWith(suffix))
                .containsExactly(new TagResponse(sharedTag, 1));
    }

    private static CreatePromptRequest prompt(Set<String> tags, Visibility visibility) {
        return new CreatePromptRequest("Tag visibility test", null, "content", tags, visibility);
    }
}
