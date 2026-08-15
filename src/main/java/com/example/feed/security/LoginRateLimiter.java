package com.example.feed.security;

import com.example.feed.api.LoginRateLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginRateLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);
    private static final DefaultRedisScript<Long> TTL_SCRIPT = new DefaultRedisScript<>(
            "return redis.call('pttl', KEYS[1])", Long.class);
    private static final DefaultRedisScript<Long> FAILURE_SCRIPT = new DefaultRedisScript<>("""
            local blocked = redis.call('pttl', KEYS[2])
            if blocked > 0 then return blocked end
            local count = redis.call('incr', KEYS[1])
            if count == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end
            if count >= tonumber(ARGV[2]) then
              redis.call('set', KEYS[2], '1', 'PX', ARGV[3])
              redis.call('del', KEYS[1])
              return tonumber(ARGV[3])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int accountMaxAttempts;
    private final int addressMaxAttempts;
    private final Duration window;
    private final Duration blockDuration;
    private final ConcurrentHashMap<String, LocalLimit> fallback = new ConcurrentHashMap<>();

    @Autowired
    public LoginRateLimiter(StringRedisTemplate redis,
                            @Value("${feed.security.login-rate-limit.account-max-attempts:5}") int accountMaxAttempts,
                            @Value("${feed.security.login-rate-limit.address-max-attempts:20}") int addressMaxAttempts,
                            @Value("${feed.security.login-rate-limit.window:15m}") Duration window,
                            @Value("${feed.security.login-rate-limit.block-duration:15m}") Duration blockDuration) {
        this(redis, Clock.systemUTC(), accountMaxAttempts, addressMaxAttempts, window, blockDuration);
    }

    LoginRateLimiter(StringRedisTemplate redis, Clock clock, int accountMaxAttempts, int addressMaxAttempts,
                     Duration window, Duration blockDuration) {
        if (accountMaxAttempts < 1 || addressMaxAttempts < 1 || window.isNegative() || window.isZero()
                || blockDuration.isNegative() || blockDuration.isZero()) {
            throw new IllegalArgumentException("登录限流配置必须为正数");
        }
        this.redis = redis;
        this.clock = clock;
        this.accountMaxAttempts = accountMaxAttempts;
        this.addressMaxAttempts = addressMaxAttempts;
        this.window = window;
        this.blockDuration = blockDuration;
    }

    public void checkAllowed(String normalizedUsername, String clientAddress) {
        long retryAfterMillis = Math.max(check(accountKey(normalizedUsername)), check(addressKey(clientAddress)));
        if (retryAfterMillis > 0) {
            throw new LoginRateLimitException(ceilSeconds(retryAfterMillis));
        }
    }

    public void recordFailure(String normalizedUsername, String clientAddress) {
        recordFailure(accountKey(normalizedUsername), accountMaxAttempts);
        recordFailure(addressKey(clientAddress), addressMaxAttempts);
    }

    public void recordSuccess(String normalizedUsername) {
        String base = accountKey(normalizedUsername);
        try {
            redis.delete(List.of(counterKey(base), blockKey(base)));
        } catch (RuntimeException exception) {
            logRedisFallback(exception);
        }
        fallback.remove(base);
    }

    private long check(String baseKey) {
        try {
            Long ttl = redis.execute(TTL_SCRIPT, List.of(blockKey(baseKey)));
            return ttl == null ? 0 : Math.max(0, ttl);
        } catch (RuntimeException exception) {
            logRedisFallback(exception);
            return checkLocal(baseKey);
        }
    }

    private void recordFailure(String baseKey, int maxAttempts) {
        try {
            redis.execute(FAILURE_SCRIPT, List.of(counterKey(baseKey), blockKey(baseKey)),
                    Long.toString(window.toMillis()), Integer.toString(maxAttempts),
                    Long.toString(blockDuration.toMillis()));
        } catch (RuntimeException exception) {
            logRedisFallback(exception);
            recordLocalFailure(baseKey, maxAttempts);
        }
    }

    private long checkLocal(String key) {
        Instant now = clock.instant();
        LocalLimit limit = fallback.get(key);
        if (limit == null) {
            return 0;
        }
        synchronized (limit) {
            if (limit.blockedUntil != null && limit.blockedUntil.isAfter(now)) {
                return Duration.between(now, limit.blockedUntil).toMillis();
            }
            if (limit.windowStarted.plus(window).isBefore(now)) {
                fallback.remove(key, limit);
            }
            return 0;
        }
    }

    private void recordLocalFailure(String key, int maxAttempts) {
        Instant now = clock.instant();
        fallback.compute(key, (ignored, current) -> {
            LocalLimit limit = current;
            if (limit == null || !limit.windowStarted.plus(window).isAfter(now)) {
                limit = new LocalLimit(now);
            }
            synchronized (limit) {
                if (limit.blockedUntil != null && limit.blockedUntil.isAfter(now)) {
                    return limit;
                }
                limit.failures++;
                if (limit.failures >= maxAttempts) {
                    limit.blockedUntil = now.plus(blockDuration);
                }
                return limit;
            }
        });
    }

    private String accountKey(String username) {
        return "feed:auth:login:account:" + sha256(username);
    }

    private String addressKey(String address) {
        return "feed:auth:login:address:" + sha256(address == null ? "unknown" : address);
    }

    private String counterKey(String base) {
        return base + ":failures";
    }

    private String blockKey(String base) {
        return base + ":blocked";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private long ceilSeconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    private void logRedisFallback(RuntimeException exception) {
        log.warn("Redis 登录限流不可用，使用本机内存限流: {}", exception.getMessage());
    }

    private static final class LocalLimit {
        private final Instant windowStarted;
        private int failures;
        private Instant blockedUntil;

        private LocalLimit(Instant windowStarted) {
            this.windowStarted = windowStarted;
        }
    }
}
