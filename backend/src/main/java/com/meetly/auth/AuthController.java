package com.meetly.auth;

import com.meetly.auth.AuthDtos.*;
import com.meetly.common.AuthProperties;
import com.meetly.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    static final String REFRESH_COOKIE = "meetly_refresh";

    private final AuthService authService;
    private final AuthProperties props;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        User user = authService.register(req.email(), req.password(), req.fullName());
        return respondWithTokens(user);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        User user = authService.authenticate(req.email(), req.password());
        return respondWithTokens(user);
    }

    ResponseEntity<AuthResponse> respondWithTokens(User user) {
        AuthService.TokenPair pair = authService.issueTokens(user);
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, pair.rawRefreshToken())
                .httpOnly(true)
                .secure(props.cookieSecure())
                .path("/api/v1/auth")
                .maxAge(props.refreshTtl())
                .sameSite("Lax")
                .build();
        AuthResponse body = new AuthResponse(pair.accessToken(),
                new UserDto(user.getId(), user.getEmail(), user.getFullName()));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).body(body);
    }
}
