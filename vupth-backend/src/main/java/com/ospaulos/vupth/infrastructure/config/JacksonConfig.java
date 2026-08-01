package com.ospaulos.vupth.infrastructure.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Second rule (BUG2b): time-of-day fields (opening_time, closing_time, fixedStartTime) are WALL
 * CLOCK in the shop's timezone, not instants. Input is tolerant (a trailing "Z" or "-03:00" is
 * accepted and IGNORED — the digits are the wall clock the user picked); output is canonical
 * "HH:MM" with no offset. See WallClockLocalTimeDeserializer/Serializer.
 *
 * IMPORTANT: modulesToInstall() is used (not modules()) so that this custom module is
 * ADDED on top of Spring Boot's auto-configured JavaTimeModule (jsr310) rather than
 * replacing it. Replacing JavaTimeModule would break OffsetDateTime serialization in
 * all response bodies (e.g. UserRegisterResponse.createdAt, ErrorResponse.timestamp).
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer apiTimeFormats() {
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addDeserializer(OffsetDateTime.class, new StrictOffsetDateTimeDeserializer());
            // BUG2b — horario de parede (opening/closing/fixedStartTime): entrada TOLERANTE,
            // saida canonica "HH:MM". Ver WallClockLocalTimeDeserializer/Serializer.
            module.addDeserializer(LocalTime.class, new WallClockLocalTimeDeserializer());
            module.addSerializer(LocalTime.class, new WallClockLocalTimeSerializer());
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

    /**
     * Horario de parede TOLERANTE na entrada (BUG2b).
     *
     * <p>{@code opening_time}, {@code closing_time} e {@code fixedStartTime} sao relogio de parede no
     * fuso da loja. O offset que os fronts mandam (ou mandavam) e <b>informativo</b>: em todos os
     * casos os DIGITOS ja sao a hora local que o usuario escolheu na tela — o front antigo mandava
     * {@code "12:00Z"} (offset errado, digitos certos) e o front corrigido manda
     * {@code "12:00-03:00"} (offset certo, digitos iguais). Logo o offset e <b>ignorado</b> e os
     * digitos entram como estao: as duas gravam 12:00, que e o que o usuario quis.</p>
     *
     * <p>Aceita {@code "HH:MM"}, {@code "HH:MM:SS"}, {@code "HH:MM:SS.sss"}, com ou sem sufixo
     * {@code Z} / {@code ±HH:MM[:SS]}. Qualquer outra coisa -> 400 {@code INVALID_REQUEST_BODY}.
     * Isso deixa o backend virar sem release lockstep dos fronts.</p>
     */
    private static class WallClockLocalTimeDeserializer extends StdDeserializer<LocalTime> {

        /** {@code HH:MM[:SS[.sss]]} + offset OPCIONAL (ignorado). */
        private static final Pattern WALL_CLOCK = Pattern.compile(
                "^(\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d{1,9})?)?)\\s*(?:Z|[+-]\\d{2}(?::?\\d{2})?(?::\\d{2})?)?$");

        protected WallClockLocalTimeDeserializer() {
            super(LocalTime.class);
        }

        @Override
        public LocalTime deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            if (p.currentToken().isNumeric()) {
                throw InvalidFormatException.from(p,
                        "Time must be a string like \"12:00\" (wall clock), not a number.",
                        p.getNumberValue(), LocalTime.class);
            }
            String value = p.getText() != null ? p.getText().trim() : "";
            Matcher matcher = WALL_CLOCK.matcher(value);
            if (!matcher.matches()) {
                throw InvalidFormatException.from(p,
                        "Invalid time format: \"" + value + "\". Expected wall clock \"HH:MM\" "
                                + "(seconds and timezone offset are optional and the offset is ignored).",
                        value, LocalTime.class);
            }
            try {
                return LocalTime.parse(normalizeHour(matcher.group(1)));
            } catch (DateTimeParseException e) {
                throw InvalidFormatException.from(p,
                        "Invalid time value: \"" + value + "\". Expected wall clock \"HH:MM\".",
                        value, LocalTime.class);
            }
        }

        /** {@code LocalTime.parse} exige 2 digitos na hora — "9:00" viraria erro sem isto. */
        private static String normalizeHour(String time) {
            return time.indexOf(':') == 1 ? "0" + time : time;
        }
    }

    /**
     * Horario de parede na saida: {@code "HH:MM"} — <b>sem</b> offset (BUG2b; antes saia
     * {@code "12:00Z"}, que sugeria falsamente um instante UTC). Segundos aparecem apenas quando
     * diferentes de zero ({@code "23:59:59"}), preservando o round-trip de janelas 24h.
     */
    private static class WallClockLocalTimeSerializer extends StdSerializer<LocalTime> {

        protected WallClockLocalTimeSerializer() {
            super(LocalTime.class);
        }

        @Override
        public void serialize(LocalTime value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeString(format(value));
        }

        static String format(LocalTime value) {
            if (value.getSecond() == 0 && value.getNano() == 0) {
                return String.format("%02d:%02d", value.getHour(), value.getMinute());
            }
            return value.toString();
        }
    }
}
