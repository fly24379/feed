package com.example.feed.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenServiceTest {
    private final RefreshTokenService tokens = new RefreshTokenService();

    @Test
    void generatesOpaqueHighEntropyTokensAndStableHashes() {
        String first = tokens.generate();
        String second = tokens.generate();

        assertThat(first).hasSize(43).isNotEqualTo(second);
        assertThat(tokens.hash(first)).hasSize(64).isEqualTo(tokens.hash(first));
        assertThat(tokens.hash(first)).isNotEqualTo(first);
    }
}
