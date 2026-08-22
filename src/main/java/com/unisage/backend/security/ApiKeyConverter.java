package com.unisage.backend.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Converter(autoApply = false)
@Component
@RequiredArgsConstructor
public class ApiKeyConverter implements AttributeConverter<String, String> {

    private final CryptoService cryptoService;

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        return cryptoService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return cryptoService.decrypt(dbData);
        } catch (Exception e) {
            // Dữ liệu cũ được lưu trước khi bật mã hoá vẫn ở dạng plaintext.
            return dbData;
        }
    }
}
