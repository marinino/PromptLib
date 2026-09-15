package de.marinic.promptlib.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.marinic.promptlib.common.error.NotFoundException;
import de.marinic.promptlib.common.page.PageResponse;
import de.marinic.promptlib.common.security.JwtService;
import de.marinic.promptlib.common.security.ProblemDetailSecurityHandlers;
import de.marinic.promptlib.common.security.SecurityConfig;
import de.marinic.promptlib.common.security.TestPrincipals;
import de.marinic.promptlib.common.security.UserDetailsServiceImpl;
import de.marinic.promptlib.prompt.dto.PromptResponse;
import de.marinic.promptlib.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// SecurityConfig (with @EnableWebSecurity) must be imported explicitly - @WebMvcTest excludes
// plain @Configuration classes from its slice, and without @EnableWebSecurity present, Spring
// never registers AuthenticationPrincipalArgumentResolver, so @AuthenticationPrincipal would
// stay unresolved. The real filter chain has to run too (no addFilters = false): the
// TestPrincipals.user(...) principal only reaches SecurityContextHolder via
// SecurityContextHolderFilter, which is part of that chain.
@WebMvcTest(PromptController.class)
@Import({SecurityConfig.class, ProblemDetailSecurityHandlers.class})
class PromptControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PromptService promptService;

    // SecurityConfig's beans (JwtAuthenticationFilter, AuthenticationManager) need these to
    // exist somewhere in the context to be constructed - never actually exercised since none
    // of these tests send a real Authorization header.
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserDetailsServiceImpl userDetailsService;

    private final UUID userId = UUID.randomUUID();

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        PromptResponse response =
                new PromptResponse(id, "Test", "desc", 1, Set.of("cv"), userId, Visibility.PRIVATE, now, now);
        given(promptService.create(any(), eq(userId))).willReturn(response);

        mockMvc.perform(
                        post("/api/v1/prompts")
                                .with(TestPrincipals.user(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"title":"Test","description":"desc","content":"Hello","tags":["cv"]}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/prompts/" + id))
                .andExpect(jsonPath("$.title").value("Test"));
    }

    @Test
    void createWithBlankTitleReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(
                        post("/api/v1/prompts")
                                .with(TestPrincipals.user(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"","content":""}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void createWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/prompts")
                                .with(TestPrincipals.user(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{not valid json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void getReturns200ForExistingPrompt() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        given(promptService.get(eq(id), eq(userId)))
                .willReturn(new PromptResponse(id, "Test", null, 1, Set.of(), userId, Visibility.PRIVATE, now, now));

        mockMvc.perform(get("/api/v1/prompts/{id}", id).with(TestPrincipals.user(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void getReturns404ForMissingPrompt() throws Exception {
        UUID id = UUID.randomUUID();
        given(promptService.get(eq(id), eq(userId)))
                .willThrow(new NotFoundException("Prompt %s not found".formatted(id)));

        mockMvc.perform(get("/api/v1/prompts/{id}", id).with(TestPrincipals.user(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // Regression tests: Spring's own client-error exceptions used to be swallowed by
    // GlobalExceptionHandler's catch-all and come back as 500.
    @Test
    void getWithNonUuidIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/prompts/{id}", "not-a-uuid").with(TestPrincipals.user(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unsupportedMethodReturns405() throws Exception {
        mockMvc.perform(
                        put("/api/v1/prompts/{id}", UUID.randomUUID())
                                .with(TestPrincipals.user(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"));
    }

    // null means "leave unchanged" for a PATCH, so @NotBlank (which also rejects null) can't be
    // used - but an explicitly sent title must still not be blank, same as on create.
    @Test
    void updateWithBlankTitleReturns400() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/prompts/{id}", UUID.randomUUID())
                                .with(TestPrincipals.user(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"title":"   "}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void updateWithoutTitleIsStillAllowed() throws Exception {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        given(promptService.update(eq(id), any(), eq(userId)))
                .willReturn(new PromptResponse(id, "Test", null, 1, Set.of(), userId, Visibility.PUBLIC, now, now));

        mockMvc.perform(
                        patch("/api/v1/prompts/{id}", id)
                                .with(TestPrincipals.user(userId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"visibility":"PUBLIC"}
                                        """))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReturns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/prompts/{id}", id).with(TestPrincipals.user(userId)))
                .andExpect(status().isNoContent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchBindsQueryParamsAndReturnsPageResponse() throws Exception {
        Instant now = Instant.now();
        PromptResponse prompt =
                new PromptResponse(
                        UUID.randomUUID(), "Video", null, 1, Set.of("cv"), userId, Visibility.PRIVATE, now, now);
        PageResponse<PromptResponse> page = new PageResponse<>(List.of(prompt), 0, 10, 1, 1);
        given(promptService.search(any(), any(), any(), any())).willReturn(page);

        mockMvc.perform(
                        get("/api/v1/prompts")
                                .with(TestPrincipals.user(userId))
                                .param("query", "video")
                                .param("tags", "cv,comfyui")
                                .param("page", "0")
                                .param("size", "10")
                                .param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Video"))
                .andExpect(jsonPath("$.totalElements").value(1));

        ArgumentCaptor<Set<String>> tagsCaptor = ArgumentCaptor.forClass(Set.class);
        verify(promptService).search(eq("video"), tagsCaptor.capture(), eq(userId), any());
        assertThat(tagsCaptor.getValue()).containsExactlyInAnyOrder("cv", "comfyui");
    }
}
