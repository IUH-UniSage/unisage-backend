package com.unisage.backend.config;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link JsonNullableModule} so {@code JsonNullable<T>} fields (used by
 * {@code ChatModelUpdateRequest.apiKey} to distinguish "field absent" from "field explicitly
 * null" — plan.md "Credential rotation") (de)serialize correctly. Spring Boot's Jackson
 * autoconfiguration picks up every {@code com.fasterxml.jackson.databind.Module} bean found in
 * the context, so declaring this bean is enough — no manual {@code ObjectMapper} wiring needed.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }
}
