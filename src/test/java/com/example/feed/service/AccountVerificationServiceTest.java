package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.VerificationChallengeRepository;
import com.example.feed.repository.VerificationChallengeRepository.Challenge;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountVerificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
    private final VerificationChallengeRepository challenges = mock(VerificationChallengeRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final VerificationCodeSender sender = mock(VerificationCodeSender.class);
    private final AccountVerificationService service = new AccountVerificationService(
            challenges, users, sender, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(10), Duration.ofSeconds(60), 5,
            "a-test-pepper-that-is-long-enough");

    @Test
    void registrationCodeIsNormalizedHashedAndCanBeConsumedOnce() {
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);

        AccountVerificationService.VerificationResponse response = service.requestRegistrationCode(
                "email", " Alice@Example.COM ", "127.0.0.1");

        verify(challenges).create(id.capture(), eq(null), eq("REGISTER"), eq("EMAIL"),
                eq("alice@example.com"), hash.capture(), eq(NOW.plusSeconds(600)), eq("127.0.0.1"));
        verify(sender).send(eq("EMAIL"), eq("alice@example.com"), code.capture(), eq("REGISTER"));
        assertThat(response.challengeId()).isEqualTo(id.getValue().toString());
        assertThat(code.getValue()).matches("\\d{6}");
        assertThat(hash.getValue()).doesNotContain(code.getValue());

        when(challenges.findForUpdate(id.getValue())).thenReturn(Optional.of(new Challenge(
                id.getValue(), null, "REGISTER", "EMAIL", "alice@example.com", hash.getValue(),
                NOW.plusSeconds(600), null, 0)));
        when(challenges.consume(id.getValue(), NOW)).thenReturn(true);

        AccountVerificationService.VerifiedContact contact = service.consumeRegistration(
                response.challengeId(), code.getValue(), "EMAIL", "ALICE@example.com");

        assertThat(contact.target()).isEqualTo("alice@example.com");
        verify(challenges).consume(id.getValue(), NOW);
    }

    @Test
    void wrongCodeCountsAsAnAttemptAndDoesNotConsumeChallenge() {
        UUID id = UUID.randomUUID();
        when(challenges.findForUpdate(id)).thenReturn(Optional.of(new Challenge(
                id, null, "REGISTER", "EMAIL", "alice@example.com", "not-the-hash",
                NOW.plusSeconds(600), null, 0)));

        assertThatThrownBy(() -> service.consumeRegistration(
                id.toString(), "000000", "EMAIL", "alice@example.com"))
                .isInstanceOf(BadRequestException.class);

        verify(challenges).recordFailure(id);
        verify(challenges, never()).consume(any(), any());
    }

    @Test
    void unknownRecoveryAccountDoesNotTriggerDelivery() {
        when(users.findRecoveryAccount("nobody")).thenReturn(Optional.empty());

        AccountVerificationService.VerificationResponse response =
                service.requestPasswordReset("nobody", "127.0.0.1");

        assertThat(response.challengeId()).isNotBlank();
        verify(sender, never()).send(any(), any(), any(), any());
        verify(challenges).create(any(), eq(null), eq("RESET_PASSWORD"), eq("EMAIL"),
                any(), any(), eq(NOW.plusSeconds(600)), eq("127.0.0.1"));
    }
}
