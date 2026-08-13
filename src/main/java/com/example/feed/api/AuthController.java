package com.example.feed.api;

import com.example.feed.security.JwtTokenService.AccessToken;
import com.example.feed.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccessToken register(@Valid @RequestBody RegisterRequest request) {
        return auth.register(request.username(), request.nickname(), request.password());
    }

    @PostMapping("/login")
    public AccessToken login(@Valid @RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}",
                    message = "username 只能包含 3-32 位字母、数字或下划线") String username,
            @NotBlank @Size(max = 80) String nickname,
            @NotBlank @Size(min = 8, max = 72) String password
    ) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank @Size(max = 72) String password
    ) {
    }
}
