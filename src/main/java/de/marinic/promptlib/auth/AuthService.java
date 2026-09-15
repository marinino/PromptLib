package de.marinic.promptlib.auth;

import de.marinic.promptlib.auth.dto.AuthResponse;
import de.marinic.promptlib.auth.dto.LoginRequest;
import de.marinic.promptlib.common.security.AppUserPrincipal;
import de.marinic.promptlib.common.security.JwtService;
import de.marinic.promptlib.user.EmailAddresses;
import de.marinic.promptlib.user.User;
import de.marinic.promptlib.user.UserService;
import de.marinic.promptlib.user.dto.RegisterRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(
            UserService userService, AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    public AuthResponse register(RegisterRequest request) {
        User user = userService.register(request);
        return new AuthResponse(jwtService.generateToken(user.getId(), user.getEmail()));
    }

    public AuthResponse login(LoginRequest request) {
        // Delegates the actual credential check to Spring Security's AuthenticationManager
        // (DaoAuthenticationProvider -> UserDetailsServiceImpl + BCrypt comparison). Throws
        // BadCredentialsException (an AuthenticationException) on mismatch - caught by
        // GlobalExceptionHandler.handleAuthentication(), not by the security filter chain,
        // since this authenticate() call happens inside a controller, not during request
        // authorization. Without that handler it would fall through to the generic
        // Exception -> 500 fallback instead of a proper 401.
        Authentication authentication =
                authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(
                                EmailAddresses.normalize(request.email()), request.password()));

        var principal = (AppUserPrincipal) authentication.getPrincipal();
        return new AuthResponse(jwtService.generateToken(principal.getId(), principal.getUsername()));
    }
}
