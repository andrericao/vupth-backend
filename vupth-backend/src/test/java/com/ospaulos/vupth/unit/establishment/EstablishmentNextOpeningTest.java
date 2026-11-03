package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentResponse;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** horario-na-listagem (BAK-19, DEC-159): a proxima abertura vai na listagem; so informacao, nao muda a regra de abertura. */
class EstablishmentNextOpeningTest {

    private Establishment estab(LocalTime opening, LocalTime closing) {
        Establishment e = new Establishment();
        e.setOpenStatus(OpenStatus.CLOSED);
        e.applySingleWindow(opening, closing);
        return e;
    }

    @Test
    @DisplayName("sem horario configurado: next_open e null")
    void notConfigured() {
        assertThat(estab(null, null).nextOpening()).isNull();
        assertThat(EstablishmentResponse.fromEntity(estab(null, null)).nextOpen()).isNull();
    }

    @Test
    @DisplayName("hoje ainda vai abrir: a proxima abertura e hoje, na hora de abrir")
    void opensLaterToday() {
        assumeTrue(BusinessClock.nowTime().isBefore(LocalTime.of(23, 58)));
        var next = estab(LocalTime.of(23, 59), LocalTime.of(23, 59, 59)).nextOpening();

        assertThat(next.date()).isEqualTo(BusinessClock.today());
        assertThat(next.opens()).isEqualTo(LocalTime.of(23, 59));
    }

    @Test
    @DisplayName("hoje ja abriu (hora passada): a proxima abertura e amanha")
    void alreadyOpenedToday() {
        assumeTrue(BusinessClock.nowTime().isAfter(LocalTime.of(0, 1)));
        var next = estab(LocalTime.of(0, 0), LocalTime.of(23, 59)).nextOpening();

        assertThat(next.date()).isEqualTo(BusinessClock.today().plusDays(1));
        assertThat(next.opens()).isEqualTo(LocalTime.of(0, 0));
    }

    @Test
    @DisplayName("a resposta da listagem traz next_open sem mudar is_open nem open_status")
    void responseCarriesNextOpen() {
        Establishment e = estab(LocalTime.of(8, 0), LocalTime.of(18, 0));
        EstablishmentResponse r = EstablishmentResponse.fromEntity(e);

        assertThat(r.nextOpen()).isNotNull();
        assertThat(r.nextOpen().opens()).isEqualTo(LocalTime.of(8, 0));
        assertThat(r.isOpen()).isFalse();
        assertThat(r.openStatus()).isEqualTo("CLOSED");
    }
}
