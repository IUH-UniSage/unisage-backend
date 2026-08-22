package com.unisage.backend.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoServiceTest {

    private final CryptoService cryptoService = new CryptoService("Unisage_Test_Encryption_Key");

    @ParameterizedTest
    @ValueSource(strings = {"sk-abc123", "", "chuỗi tiếng Việt có dấu", "a-very-long-api-key-1234567890-1234567890-1234567890"})
    void encryptThenDecrypt_returnsOriginalPlainText(String plainText) {
        String encrypted = cryptoService.encrypt(plainText);
        String decrypted = cryptoService.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plainText);
    }

    @Test
    void encrypt_doesNotReturnPlainTextValue() {
        String plainText = "sk-super-secret-api-key";

        String encrypted = cryptoService.encrypt(plainText);

        assertThat(encrypted).doesNotContain(plainText);
    }

    @Test
    void encrypt_isNonDeterministic_dueToRandomIv() {
        String plainText = "sk-super-secret-api-key";

        String first = cryptoService.encrypt(plainText);
        String second = cryptoService.encrypt(plainText);

        assertThat(first).isNotEqualTo(second);
        assertThat(cryptoService.decrypt(first)).isEqualTo(plainText);
        assertThat(cryptoService.decrypt(second)).isEqualTo(plainText);
    }
}
