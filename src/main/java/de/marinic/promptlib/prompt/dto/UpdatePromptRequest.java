package de.marinic.promptlib.prompt.dto;

import de.marinic.promptlib.prompt.Visibility;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * Every field is optional: null means "leave unchanged". That rules out @NotBlank on title (it
 * rejects null too) - @Pattern, like most constraints, treats null as valid and only checks a
 * value that was actually sent, so a present title must contain at least one non-whitespace
 * character, same rule as on create.
 */
public record UpdatePromptRequest(
        @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank") String title,
        String description,
        Set<String> tags,
        Visibility visibility) {}
