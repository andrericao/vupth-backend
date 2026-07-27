package com.ospaulos.vupth.unit.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.ospaulos.vupth.infrastructure.config.JacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests — contrato JSON do horario de PAREDE (BUG2b).
 *
 * <p>{@code opening_time}, {@code closing_time} e {@code fixedStartTime} sao hora do dia no fuso da
 * loja, nao instantes. O contrato com os fronts tem dois lados: <b>entrada tolerante</b> (o sufixo
 * de offset — legado {@code "Z"} do front antigo ou {@code "-03:00"} do front corrigido — e aceito
 * e IGNORADO, porque em ambos os casos os DIGITOS ja sao a hora que o usuario escolheu na tela) e
 * <b>saida canonica</b> {@code "HH:MM"} sem offset. E isso que permite o backend virar sem release
 * em lockstep com os fronts.</p>
 */
class WallClockTimeJsonTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().apiTimeFormats().customize(builder);
        this.mapper = builder.build();
    }

    private LocalTime read(String json) throws Exception {
        return mapper.readValue("\"" + json + "\"", LocalTime.class);
    }

    @Nested
    @DisplayName("Entrada — os digitos valem, o offset e ignorado")
    class Entrada {

        @Test
        @DisplayName("as tres formas que os fronts mandam viram a MESMA hora de parede")
        void offsetIsIgnored() throws Exception {
            // front antigo: offset errado, digitos certos | front corrigido: offset certo, digitos
            // iguais | forma canonica nova. As tres significam "meio-dia no relogio da loja".
            assertThat(read("12:00Z")).isEqualTo(LocalTime.of(12, 0));
            assertThat(read("12:00-03:00")).isEqualTo(LocalTime.of(12, 0));
            assertThat(read("12:00")).isEqualTo(LocalTime.of(12, 0));
        }

        @Test
        @DisplayName("aceita segundos, milissegundos e offset sem dois-pontos")
        void acceptsOptionalPrecisionAndOffsetShapes() throws Exception {
            assertThat(read("23:59:59")).isEqualTo(LocalTime.of(23, 59, 59));
            assertThat(read("08:30:00.500Z")).isEqualTo(LocalTime.of(8, 30, 0, 500_000_000));
            assertThat(read("08:30:00-0300")).isEqualTo(LocalTime.of(8, 30));
        }

        @Test
        @DisplayName("hora de 1 digito e normalizada (\"9:00\" -> 09:00)")
        void normalizesSingleDigitHour() throws Exception {
            assertThat(read("9:00")).isEqualTo(LocalTime.of(9, 0));
        }

        @Test
        @DisplayName("lixo e numero sao rejeitados (viram 400 INVALID_REQUEST_BODY)")
        void rejectsGarbage() {
            assertThatThrownBy(() -> read("meio-dia")).isInstanceOf(InvalidFormatException.class);
            assertThatThrownBy(() -> read("2026-07-26T12:00:00Z")).isInstanceOf(InvalidFormatException.class);
            assertThatThrownBy(() -> read("25:00")).isInstanceOf(InvalidFormatException.class);
            assertThatThrownBy(() -> mapper.readValue("43200", LocalTime.class))
                    .isInstanceOf(InvalidFormatException.class);
        }
    }

    @Nested
    @DisplayName("Saida — canonica, sem offset")
    class Saida {

        @Test
        @DisplayName("\"HH:MM\" — nunca mais \"12:00Z\", que sugeria um instante UTC")
        void serializesWithoutOffset() throws Exception {
            assertThat(mapper.writeValueAsString(LocalTime.of(12, 0))).isEqualTo("\"12:00\"");
            assertThat(mapper.writeValueAsString(LocalTime.of(9, 5))).isEqualTo("\"09:05\"");
        }

        @Test
        @DisplayName("segundos so aparecem quando existem — preserva a janela 24h [00:00, 23:59:59)")
        void keepsSecondsWhenPresent() throws Exception {
            assertThat(mapper.writeValueAsString(LocalTime.of(23, 59, 59))).isEqualTo("\"23:59:59\"");
            assertThat(mapper.writeValueAsString(LocalTime.MIDNIGHT)).isEqualTo("\"00:00\"");
        }

        @Test
        @DisplayName("round-trip: o que sai volta igual")
        void roundTrips() throws Exception {
            for (LocalTime t : new LocalTime[]{LocalTime.MIDNIGHT, LocalTime.of(8, 30),
                    LocalTime.of(18, 0), LocalTime.of(23, 59, 59)}) {
                assertThat(read(mapper.writeValueAsString(t).replace("\"", ""))).isEqualTo(t);
            }
        }
    }
}
