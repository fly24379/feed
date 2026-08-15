package com.example.feed.api;

import com.example.feed.service.AccountVerificationService;
import com.example.feed.service.AccountVerificationService.VerificationResponse;
import com.example.feed.service.AuthService;
import com.example.feed.service.AuthService.AuthTokens;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final AccountVerificationService verification;

    public AuthController(AuthService auth, AccountVerificationService verification) {
        this.auth = auth;
        this.verification = verification;
    }

    @PostMapping("/verification/register/request")
    @ResponseStatus(HttpStatus.CREATED)
    public VerificationResponse requestRegistrationCode(
            @Valid @RequestBody RegistrationCodeRequest request,
            HttpServletRequest servletRequest) {
        return verification.requestRegistrationCode(
                request.channel(), request.target(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthTokens register(@Valid @RequestBody RegisterRequest request,
                               HttpServletRequest servletRequest) {
        return auth.registerVerified(request.username(), request.nickname(), request.password(),
                request.channel(), request.target(), request.challengeId(), request.verificationCode(),
                servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
    }

    @PostMapping("/login")
    public AuthTokens login(@Valid @RequestBody LoginRequest request,
                            HttpServletRequest servletRequest) {
        return auth.login(request.username(), request.password(),
                servletRequest.getRemoteAddr(), servletRequest.getHeader("User-Agent"));
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.CREATED)
    public VerificationResponse requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest servletRequest) {
        return verification.requestPasswordReset(request.account(), servletRequest.getRemoteAddr());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
        auth.resetPassword(request.challengeId(), request.verificationCode(), request.newPassword());
    }

    @PostMapping("/refresh")
    public AuthTokens refresh(@Valid @RequestBody RefreshRequest request) {
        return auth.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal Jwt jwt) {
        auth.logout(jwt.getClaimAsString("sid"), Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@Valid @RequestBody RefreshRequest request) {
        auth.revoke(request.refreshToken());
    }

    public record RegistrationCodeRequest(
            @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
            @NotBlank @Size(max = 254) String target
    ) {
    }

    public record RegisterRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}",
                    message = "username 只能包含 3-32 位字母、数字或下划线") String username,
            @NotBlank @Size(max = 80) String nickname,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
            @NotBlank @Size(max = 254) String target,
            @NotBlank @Size(max = 36) String challengeId,
            @NotBlank @Pattern(regexp = "\\d{6}") String verificationCode
    ) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank @Size(max = 72) String password
    ) {
    }

    public record PasswordResetRequest(@NotBlank @Size(max = 254) String account) {
    }

    public record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 36) String challengeId,
            @NotBlank @Pattern(regexp = "\\d{6}") String verificationCode,
            @NotBlank @Size(min = 8, max = 72) String newPassword
    ) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {
    }
}
