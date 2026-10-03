package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.event.WorkStatusChangedEvent;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalShift;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalShiftRepository;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalShiftRecorder;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus.OFF;
import static com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus.ON_BREAK;
import static com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus.WORKING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Expediente real (Partner S4): o turno abre na entrada em expediente e fecha na volta a Fora. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Expediente — registro dos turnos")
class ProfessionalShiftRecorderTest {

    @Mock ProfessionalShiftRepository shiftRepository;
    @InjectMocks ProfessionalShiftRecorder recorder;

    private final UUID professionalId = UUID.randomUUID();
    private final UUID establishmentId = UUID.randomUUID();
    private final OffsetDateTime at = OffsetDateTime.parse("2026-10-02T09:00:00-03:00");

    private WorkStatusChangedEvent event(com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus from,
                                         com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus to) {
        return new WorkStatusChangedEvent(professionalId, establishmentId, from, to, at);
    }

    @Test
    @DisplayName("Fora -> Trabalhando abre um turno com a loja e a hora")
    void startsShift() {
        when(shiftRepository.findByProfessionalIdAndEndedAtIsNull(professionalId)).thenReturn(List.of());

        recorder.on(event(OFF, WORKING));

        ArgumentCaptor<ProfessionalShift> saved = ArgumentCaptor.forClass(ProfessionalShift.class);
        verify(shiftRepository).save(saved.capture());
        assertThat(saved.getValue().getProfessionalId()).isEqualTo(professionalId);
        assertThat(saved.getValue().getEstablishmentId()).isEqualTo(establishmentId);
        assertThat(saved.getValue().getStartedAt()).isEqualTo(at);
        assertThat(saved.getValue().isOpen()).isTrue();
        assertThat(saved.getValue().getId()).isNull();
    }

    @Test
    @DisplayName("com turno ja aberto, a entrada nao duplica")
    void startsShift_alreadyOpen() {
        when(shiftRepository.findByProfessionalIdAndEndedAtIsNull(professionalId))
                .thenReturn(List.of(new ProfessionalShift(professionalId, establishmentId, at.minusHours(1))));

        recorder.on(event(OFF, ON_BREAK));

        verify(shiftRepository, never()).save(any());
    }

    @Test
    @DisplayName("Intervalo -> Fora fecha o turno aberto")
    @SuppressWarnings("unchecked")
    void endsShift() {
        ProfessionalShift open = new ProfessionalShift(professionalId, establishmentId, at.minusHours(8));
        when(shiftRepository.findByProfessionalIdAndEndedAtIsNull(professionalId)).thenReturn(List.of(open));

        recorder.on(event(ON_BREAK, OFF));

        assertThat(open.getEndedAt()).isEqualTo(at);
        assertThat(open.isOpen()).isFalse();
        ArgumentCaptor<Collection<ProfessionalShift>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(shiftRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).containsExactly(open);
    }

    @Test
    @DisplayName("Trabalhando <-> Intervalo nao mexe no turno")
    void breakIsInsideShift() {
        recorder.on(event(WORKING, ON_BREAK));
        recorder.on(event(ON_BREAK, WORKING));

        verifyNoInteractions(shiftRepository);
    }

    @Test
    @DisplayName("o evento sabe quando o turno comeca e termina (status nulo nao conta)")
    void eventTransitions() {
        assertThat(event(null, WORKING).startsShift()).isTrue();
        assertThat(event(WORKING, null).endsShift()).isTrue();
        assertThat(event(OFF, OFF).startsShift()).isFalse();
        assertThat(event(OFF, OFF).endsShift()).isFalse();
    }

    @Test
    @DisplayName("Professional.setWorkStatus registra o evento so quando o status muda")
    void professionalRegistersEvent() {
        BaseUser user = new BaseUser();
        user.setId(professionalId);
        Professional professional = new Professional(user);
        Establishment establishment = new Establishment();
        establishment.setId(establishmentId);

        professional.setWorkStatus(OFF); // nasce OFF — nada muda
        assertThat(domainEvents(professional)).isEmpty();

        professional.setWorkStatus(WORKING); // sem loja ainda
        professional.setEstablishment(establishment);
        professional.setWorkStatus(OFF);

        List<Object> events = domainEvents(professional);
        assertThat(events).hasSize(2);
        WorkStatusChangedEvent first = (WorkStatusChangedEvent) events.get(0);
        assertThat(first.professionalId()).isEqualTo(professionalId);
        assertThat(first.establishmentId()).isNull();
        assertThat(first.from()).isEqualTo(OFF);
        assertThat(first.to()).isEqualTo(WORKING);
        assertThat(first.at()).isNotNull();
        WorkStatusChangedEvent second = (WorkStatusChangedEvent) events.get(1);
        assertThat(second.establishmentId()).isEqualTo(establishmentId);
        assertThat(second.endsShift()).isTrue();
        assertThat(professional.getWorkStatus()).isEqualTo(OFF);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> domainEvents(Professional professional) {
        return List.copyOf((Collection<Object>) ReflectionTestUtils.invokeMethod(professional, "domainEvents"));
    }
}
