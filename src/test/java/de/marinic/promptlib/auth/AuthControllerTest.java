package de.marinic.promptlib.auth;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.marinic.promptlib.auth.dto.AuthResponse;
import de.marinic.promptlib.common.security.JwtService;
import de.marinic.promptlib.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AuthService authService;

    // See PromptControllerTest for why these two unused mocks are needed here.
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;

    @Test
    void registerReturns201WithToken() throws Exception {
        given(authService.register(org.mockito.ArgumentMatchers.any())).willReturn(new AuthResponse("a-jwt-token"));

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"noah@example.com","password":"supersecret"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").value("a-jwt-token"));
    }

    @Test
    void registerWithShortPasswordReturns400() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"noah@example.com","password":"short"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void loginReturns200WithToken() throws Exception {
        given(authService.login(org.mockito.ArgumentMatchers.any())).willReturn(new AuthResponse("a-jwt-token"));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"noah@example.com","password":"supersecret"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("a-jwt-token"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        given(authService.login(org.mockito.ArgumentMatchers.any()))
                .willThrow(new BadCredentialsException("bad credentials"));

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email":"noah@example.com","password":"wrong"}
                                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    // Regression test: a plain HTML form posting to this JSON endpoint used to come back as
    // 500 "An unexpected error occurred" (logged as ERROR), because GlobalExceptionHandler's
    // catch-all swallowed Spring's own HttpMediaTypeNotSupportedException before Spring
    // could turn it into the 415 it already carries.
    @Test
    void loginWithFormBodyReturns415() throws Exception {
        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .content("email=noah%40example.com&password=supersecret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }
}
