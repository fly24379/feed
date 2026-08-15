package com.example.feed.service;

import com.example.feed.api.BadRequestException;
import com.example.feed.api.ConflictException;
import com.example.feed.api.VerificationRateLimitException;
import com.example.feed.repository.UserRepository;
import com.example.feed.repository.UserRepository.RecoveryAccount;
import com.example.feed.repository.VerificationChallengeRepository;
import com.example.feed.repository.VerificationChallengeRepository.Challenge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AccountVerificationService {
    public static final String REGISTER = "REGISTER";
    public static final String RESET_PASSWORD = "RESET_PASSWORD";
    public static final String EMAIL = "EMAIL";
    public static final String PHONE = "PHONE";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?(?:\\.[A-Z0-9](?:[A-Z0-9-]{0,61}[A-Z0-9])?)+$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    private final VerificationChallengeRepository challenges;
    private final UserRepository users;
    private final VerificationCodeSender sender;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;
    private final Duration resendCooldown;
    private final int maxAttempts;
    private final String pepper;

    public AccountVerificationService(VerificationChallengeRepository challenges, UserRepository users,
                                      VerificationCodeSender sender,
                                      @Value("${feed.security.verification.ttl:10m}") Duration ttl,
                                      @Value("${feed.security.verification.resend-cooldown:60s}") Duration resendCooldown,
                                      @Value("${feed.security.verification.max-attempts:5}") int maxAttempts,
                                      @Value("${feed.security.jwt.secret}") String pepper) {
        this(challenges, users, sender, Clock.systemUTC(), ttl, resendCooldown, maxAttempts, pepper);
    }

    AccountVerificationService(VerificationChallengeRepository challenges, UserRepository users,
                               VerificationCodeSender sender, Clock clock, Duration ttl,
                               Duration resendCooldown, int maxAttempts, String pepper) {
        if (ttl.isZero() || ttl.isNegative() || resendCooldown.isNegative() || maxAttempts < 1) {
            throw new IllegalArgumentException("验证码安全参数无效");
        }
        this.challenges = challenges;
        this.users = users;
        this.sender = sender;
        this.clock = clock;
        this.ttl = ttl;
        this.resendCooldown = resendCooldown;
        this.maxAttempts = maxAttempts;
        this.pepper = pepper;
    }

    @Transactional
    public VerificationResponse requestRegistrationCode(String rawChannel, String rawTarget,
                                                        String clientAddress) {
        String channel = normalizeChannel(rawChannel);
        String target = normalizeTarget(channel, rawTarget);
        if (users.existsByVerifiedContact(channel, target)) {
            throw new ConflictException("该邮箱或手机号已被使用");
        }
        return createChallenge(null, REGISTER, channel, target, clientAddress, true);
    }

    @Transactional
    public VerificationResponse requestPasswordReset(String rawAccount, String clientAddress) {
        String account = normalizeAccount(rawAccount);
        Optional<RecoveryAccount> found = users.findRecoveryAccount(account);
        Contact contact = found.flatMap(user -> recoveryContact(user, account)).orElse(null);
        String channel = contact == null ? EMAIL : contact.channel();
        String target = contact == null ? "missing:" + digest(account) : contact.target();
        Long userId = contact == null ? null : found.orElseThrow().id();
        return createChallenge(userId, RESET_PASSWORD, channel, target, clientAddress, contact != null);
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public VerifiedContact consumeRegistration(String challengeId, String code,
                                               String rawChannel, String rawTarget) {
        Challenge challenge = requireValid(challengeId, code, REGISTER);
        String channel = normalizeChannel(rawChannel);
        String target = normalizeTarget(channel, rawTarget);
        if (!channel.equals(challenge.channel()) || !target.equals(challenge.target())) {
            throw new BadRequestException("验证码与邮箱或手机号不匹配");
        }
        consume(challenge);
        return new VerifiedContact(channel, target, clock.instant());
    }

    @Transactional(noRollbackFor = BadRequestException.class)
    public long consumePasswordReset(String challengeId, String code) {
        Challenge challenge = requireValid(challengeId, code, RESET_PASSWORD);
        if (challenge.userId() == null) {
            throw new BadRequestException("验证码无效或已过期");
        }
        consume(challenge);
        return challenge.userId();
    }

    private VerificationResponse createChallenge(Long userId, String purpose, String channel,
                                                 String target, String clientAddress, boolean deliver) {
        Instant now = clock.instant();
        if (challenges.hasRecent(purpose, target, now.minus(resendCooldown))) {
            throw new VerificationRateLimitException(Math.max(1, resendCooldown.toSeconds()));
        }
        UUID id = UUID.randomUUID();
        String code = "%06d".formatted(random.nextInt(1_000_000));
        challenges.create(id, userId, purpose, channel, target, hash(id, code), now.plus(ttl),
                truncate(clientAddress, 64));
        if (deliver) {
            sender.send(channel, target, code, purpose);
        }
        return new VerificationResponse(id.toString(), Math.max(1, ttl.toSeconds()),
                Math.max(1, resendCooldown.toSeconds()));
    }

    private Challenge requireValid(String rawId, String code, String purpose) {
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("验证码无效或已过期");
        }
        Challenge challenge = challenges.findForUpdate(id)
                .orElseThrow(() -> new BadRequestException("验证码无效或已过期"));
        Instant now = clock.instant();
        if (!purpose.equals(challenge.purpose()) || challenge.consumedAt() != null
                || !challenge.expiresAt().isAfter(now) || challenge.attempts() >= maxAttempts) {
            throw new BadRequestException("验证码无效或已过期");
        }
        byte[] expected = challenge.codeHash().getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(challenge.id(), code).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, actual)) {
            challenges.recordFailure(challenge.id());
            throw new BadRequestException("验证码无效或已过期");
        }
        return challenge;
    }

    private void consume(Challenge challenge) {
        if (!challenges.consume(challenge.id(), clock.instant())) {
            throw new BadRequestException("验证码无效或已过期");
        }
    }

    private Optional<Contact> recoveryContact(RecoveryAccount user, String account) {
        if (user.emailVerified() && account.equals(user.email())) {
            return Optional.of(new Contact(EMAIL, user.email()));
        }
        if (user.phoneVerified() && account.equals(user.phone())) {
            return Optional.of(new Contact(PHONE, user.phone()));
        }
        if (user.emailVerified()) {
            return Optional.of(new Contact(EMAIL, user.email()));
        }
        if (user.phoneVerified()) {
            return Optional.of(new Contact(PHONE, user.phone()));
        }
        return Optional.empty();
    }

    private String normalizeAccount(String value) {
        String clean = value == null ? "" : value.strip();
        if (clean.isBlank()) {
            throw new BadRequestException("请输入用户名、邮箱或手机号");
        }
        if (clean.contains("@")) {
            return clean.toLowerCase(Locale.ROOT);
        }
        String phone = clean.replaceAll("[\\s()-]", "");
        return phone.startsWith("+") ? phone : clean.toLowerCase(Locale.ROOT);
    }

    private String normalizeChannel(String value) {
        String channel = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!EMAIL.equals(channel) && !PHONE.equals(channel)) {
            throw new BadRequestException("验证方式必须是 EMAIL 或 PHONE");
        }
        return channel;
    }

    private String normalizeTarget(String channel, String value) {
        String target = value == null ? "" : value.strip();
        if (EMAIL.equals(channel)) {
            target = target.toLowerCase(Locale.ROOT);
            if (target.length() > 254 || !EMAIL_PATTERN.matcher(target).matches()) {
                throw new BadRequestException("邮箱格式不正确");
            }
        } else {
            target = target.replaceAll("[\\s()-]", "");
            if (!PHONE_PATTERN.matcher(target).matches()) {
                throw new BadRequestException("手机号需包含国家码，例如 +8613812345678");
            }
        }
        return target;
    }

    private String hash(UUID id, String code) {
        return digest(id + ":" + code);
    }

    private String digest(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (pepper + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String clean = value.strip();
        return clean.length() <= maxLength ? clean : clean.substring(0, maxLength);
    }

    private record Contact(String channel, String target) {
    }

    public record VerifiedContact(String channel, String target, Instant verifiedAt) {
    }

    public record VerificationResponse(String challengeId, long expiresIn, long resendAfter) {
    }
}
