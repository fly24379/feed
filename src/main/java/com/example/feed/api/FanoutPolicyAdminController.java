package com.example.feed.api;

import com.example.feed.domain.FanoutMode;
import com.example.feed.repository.FanoutPolicyRepository.FanoutPolicy;
import com.example.feed.service.FanoutPolicyService;
import com.example.feed.service.FanoutPolicyService.FanoutSwitchResult;
import com.example.feed.service.FanoutAutoPolicyJob;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/fanout-policies")
@PreAuthorize("hasRole('ADMIN')")
public class FanoutPolicyAdminController {
    private final FanoutPolicyService policies;
    private final FanoutAutoPolicyJob automation;

    public FanoutPolicyAdminController(FanoutPolicyService policies, FanoutAutoPolicyJob automation) {
        this.policies = policies;
        this.automation = automation;
    }

    @GetMapping("/automation")
    public FanoutAutoPolicyJob.Snapshot automation() {
        return automation.snapshot();
    }

    @PostMapping("/automation/run")
    public FanoutAutoPolicyJob.Snapshot runAutomation() {
        return automation.refresh();
    }

    @GetMapping("/{authorId}")
    public FanoutPolicy get(@PathVariable long authorId) {
        return policies.get(authorId);
    }

    @PutMapping("/{authorId}")
    public FanoutPolicy set(@PathVariable long authorId,
                            @Valid @RequestBody UpdateFanoutPolicyRequest request) {
        return policies.set(authorId, request.mode(), request.reason());
    }

    @PostMapping("/{authorId}/switch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public FanoutSwitchResult switchMode(@PathVariable long authorId,
                                         @Valid @RequestBody SwitchFanoutPolicyRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return policies.switchMode(authorId, request.mode(), request.reason(),
                request.historyLimit(), Long.parseLong(jwt.getSubject()));
    }

    @DeleteMapping("/{authorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable long authorId) {
        policies.reset(authorId);
    }

    public record UpdateFanoutPolicyRequest(
            @NotNull FanoutMode mode,
            @Size(max = 128) String reason
    ) {
    }

    public record SwitchFanoutPolicyRequest(
            @NotNull FanoutMode mode,
            @Size(max = 128) String reason,
            @PositiveOrZero Long historyLimit
    ) {
    }
}
