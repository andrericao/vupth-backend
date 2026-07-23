package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalAvailability;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.ospaulos.vupth.domain.professional.valueObject.ProfessionalAvailability.DISPONIVEL;
import static com.ospaulos.vupth.domain.professional.valueObject.ProfessionalAvailability.ENCERRANDO;
import static com.ospaulos.vupth.domain.professional.valueObject.ProfessionalAvailability.INDISPONIVEL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit — S1 (fatia 4): rotulo do cliente derivado de {@code work_status} + {@code accepting_new}.
 * Invariante: DISPONIVEL ⇔ o walk-in do cliente novo passaria pelos gates do profissional.
 */
class ProfessionalAvailabilityTest {

    @Test
    @DisplayName("WORKING + aceitando -> DISPONIVEL")
    void workingAccepting_disponivel() {
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.WORKING, true))
                .isEqualTo(DISPONIVEL);
    }

    @Test
    @DisplayName("ON_BREAK + aceitando -> DISPONIVEL (sem rotulo 'EM INTERVALO'; a espera embute o intervalo)")
    void onBreakAccepting_disponivel() {
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.ON_BREAK, true))
                .isEqualTo(DISPONIVEL);
    }

    @Test
    @DisplayName("WORKING + fila fechada -> ENCERRANDO (drenando)")
    void workingClosed_encerrando() {
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.WORKING, false))
                .isEqualTo(ENCERRANDO);
    }

    @Test
    @DisplayName("ON_BREAK + fila fechada -> ENCERRANDO (o gate 409 nao distingue WORKING/ON_BREAK)")
    void onBreakClosed_encerrando() {
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.ON_BREAK, false))
                .isEqualTo(ENCERRANDO);
    }

    @Test
    @DisplayName("OFF -> INDISPONIVEL (mesmo com accepting_new=true residual)")
    void off_indisponivel() {
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.OFF, true))
                .isEqualTo(INDISPONIVEL);
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.OFF, false))
                .isEqualTo(INDISPONIVEL);
    }

    @Test
    @DisplayName("nulls defensivos: status null -> INDISPONIVEL; accepting null -> aceita (default true)")
    void nulls_defensive() {
        assertThat(ProfessionalAvailability.derive(null, true)).isEqualTo(INDISPONIVEL);
        assertThat(ProfessionalAvailability.derive(ProfessionalStatus.WORKING, null))
                .isEqualTo(DISPONIVEL);
    }
}
