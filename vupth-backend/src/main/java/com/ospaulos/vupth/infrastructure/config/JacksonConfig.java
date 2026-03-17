package com.ospaulos.vupth.infrastructure.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Jackson configuration for the API.
 *
 * Key rule: OffsetDateTime fields in request bodies MUST be ISO-8601 strings
 * (e.g. "2025-06-15T10:00:00Z"). Numeric values (epoch timestamps) are rejected
 * with HTTP 400 — they would otherwise silently produce unexpected dates from 1970.
 *
 * This prevents the pattern where a client accidentally sends:
 *   "scheduledTime": 12345   ← rejected → 400 INVALID_REQUEST_BODY
 * instead of:
 *   "scheduledTime": "2025-06-15T10:00:00Z"  ← accepted
 *
 * IMPORTANT: modulesToInstall() is used (not modules()) so that this custom module is
 * ADDED on top of Spring Boot's auto-configured JavaTimeModule (jsr310) rather than
 * replacing it. Replacing JavaTimeModule would break OffsetDateTime serialization in
 * all response bodies (e.g. UserRegisterResponse.createdAt, ErrorResponse.timestamp).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer offsetDateTimeRejectsNumbers() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addDeserializer(OffsetDateTime.class, new StrictOffsetDateTimeDeserializer());
            // modulesToInstall ADDS to existing modules — preserves JavaTimeModule for serialization.
            // DO NOT use builder.modules(module) — that replaces ALL modules including JavaTimeModule.
            builder.modulesToInstall(module);
        };
    }

    /**
     * Deserializer that only accepts ISO-8601 strings for OffsetDateTime.
     * Numeric tokens (integers or floats) are rejected with InvalidFormatException,
     * which Spring translates to HTTP 400 via GlobalExceptionHandler.
     */
    private static class StrictOffsetDateTimeDeserializer extends StdDeserializer<OffsetDateTime> {

        protected StrictOffsetDateTimeDeserializer() {
            super(OffsetDateTime.class);
        }

        @Override
        public OffsetDateTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken().isNumeric()) {
                throw InvalidFormatException.from(p,
                        "OffsetDateTime must be an ISO-8601 string (e.g. \"2025-06-15T10:00:00Z\"), not a number.",
                        p.getNumberValue(), OffsetDateTime.class);
            }
            String value = p.getText();
            try {
                return OffsetDateTime.parse(value);
            } catch (DateTimeParseException e) {
                throw InvalidFormatException.from(p,
                        "Invalid date-time format: \"" + value + "\". Expected ISO-8601 (e.g. \"2025-06-15T10:00:00Z\").",
                        value, OffsetDateTime.class);
            }
        }
    }
}
