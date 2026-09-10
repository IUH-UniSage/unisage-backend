package com.unisage.backend.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenHashUtilTest {

    private final TokenHashUtil tokenHashUtil = new TokenHashUtil();

    @Test
    void sha256Hex_sameInput_producesSameHash() {
        String token = "some-raw-token-value";

        assertThat(tokenHashUtil.sha256Hex(token)).isEqualTo(tokenHashUtil.sha256Hex(token));
    }

    @Test
    void sha256Hex_differentInput_producesDifferentHash() {
        assertThat(tokenHashUtil.sha256Hex("token-a")).isNotEqualTo(tokenHashUtil.sha256Hex("token-b"));
    }

    @Test
    void sha256Hex_isLowercaseHex64Chars() {
        String hash = tokenHashUtil.sha256Hex("anything");

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void generateToken_isUrlSafeAndHighEntropy() {
        String tokenA = tokenHashUtil.generateToken();
        String tokenB = tokenHashUtil.generateToken();

        assertThat(tokenA).isNotEqualTo(tokenB);
        assertThat(tokenA).matches("[A-Za-z0-9_-]+"); // Base64URL alphabet, no padding
        assertThat(tokenA.length()).isGreaterThanOrEqualTo(40); // 256 bits Base64URL-encoded
    }
}
