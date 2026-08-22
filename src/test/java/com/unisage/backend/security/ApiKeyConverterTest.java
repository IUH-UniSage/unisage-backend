package com.unisage.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyConverterTest {

    private final CryptoService cryptoService = new CryptoService("Unisage_Test_Encryption_Key");
    private final ApiKeyConverter converter = new ApiKeyConverter(cryptoService);

    @Test
    void convertToDatabaseColumn_encryptsValue() {
        String plainApiKey = "sk-super-secret-api-key";

        String stored = converter.convertToDatabaseColumn(plainApiKey);

        assertThat(stored).isNotEqualTo(plainApiKey);
    }

    @Test
    void roundTrip_returnsOriginalApiKey() {
        String plainApiKey = "sk-super-secret-api-key";

        String stored = converter.convertToDatabaseColumn(plainApiKey);
        String read = converter.convertToEntityAttribute(stored);

        assertThat(read).isEqualTo(plainApiKey);
    }

    @Test
    void convertToEntityAttribute_treatsUndecryptableLegacyValueAsPlainText() {
        String legacyPlainTextValue = "sk-legacy-plaintext-key";

        String read = converter.convertToEntityAttribute(legacyPlainTextValue);

        assertThat(read).isEqualTo(legacyPlainTextValue);
    }

    @Test
    void convertToDatabaseColumn_nullStaysNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertToEntityAttribute_nullStaysNull() {
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
