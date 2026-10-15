package com.ospaulos.vupth.unit.professional;

import com.ospaulos.vupth.domain.events.CreateRecurringIntervalCommand;
import com.ospaulos.vupth.domain.events.CreateUnexpectedIntervalCommand;
import com.ospaulos.vupth.domain.interval.exception.IntervalConflictException;
import com.ospaulos.vupth.domain.interval.exception.InvalidIntervalTimeException;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.service.impl.ProfessionalIntervalService;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ProfessionalIntervalService — refeicoes e pausas sem banco (cobertura-98): a proxima refeicao do
 * Modelo B, o "nao almocar", o encerrar ao atender, os conflitos de horario e a edicao parcial.
 *
 * <p>O profissional daqui nao tem loja: sem fechamento nem outra refeicao depois, a refeicao nunca
 * fica suprimida, entao os casos nao dependem da hora em que a suite roda.</p>
 */
@ExtendWith(MockitoExtension.class)
class ProfessionalIntervalServiceTest {

    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ProfessionalRepository professionalRepository;
    @Mock ProfessionalIntervalRepository intervalRepository;

    @InjectMocks ProfessionalIntervalService intervalService;

    private UUID professionalId;
    private Professional professional;

    @BeforeEach
    void setUp() {
        professionalId = UUID.randomUUID();
        BaseUser user = new BaseUser();
        user.setId(professionalId);
        user.setFirstName("Carlos");
        professional = new Professional(user);
    }

    private ProfessionalInterval meal(LocalTime start) {
        ProfessionalInterval iv = ProfessionalInterval.createRecurring(professional, 60, start, "WEEKDAYS", "Almoco");
        iv.setId(UUID.randomUUID());
        return iv;
    }

    private ProfessionalInterval unexpected() {
        ProfessionalInterval iv = ProfessionalInterval.createUnexpected(
                professional, 15, OffsetDateTime.now().plusHours(1), "Pausa");
        iv.setId(UUID.randomUUID());
        return iv;
    }

    private void saveReturnsArgument() {
        when(intervalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("startNextMeal")
    class StartNextMeal {

        @Test
        @DisplayName("sem refeicao recorrente sobrando -> IntervalConflictException")
        void nothingLeft() {
            ProfessionalInterval semHorario = meal(null);
            ProfessionalInterval pulada = meal(LocalTime.of(18, 0));
            pulada.skipOn(BusinessClock.today());
            when(intervalRepository.findByProfessional_Id(professionalId))
                    .thenReturn(List.of(unexpected(), semHorario, pulada));

            assertThatThrownBy(() -> intervalService.startNextMeal(professionalId))
                    .isInstanceOf(IntervalConflictException.class);
        }

        @Test
        @DisplayName("inicia a refeicao recorrente que sobrou")
        void startsTheRemainingMeal() {
            ProfessionalInterval lunch = meal(LocalTime.of(12, 0));
            when(intervalRepository.findByProfessional_Id(professionalId)).thenReturn(List.of(unexpected(), lunch));
            when(intervalRepository.findById(lunch.getId())).thenReturn(Optional.of(lunch));
            saveReturnsArgument();

            ProfessionalInterval started = intervalService.startNextMeal(professionalId);

            assertThat(started).isSameAs(lunch);
            assertThat(lunch.getIsActive()).isTrue();
            assertThat(lunch.getActualStartTime()).isNotNull();
        }
    }

    @Test
    @DisplayName("startInterval de refeicao ja encerrada hoje -> IntervalConflictException")
    void start_alreadyDoneToday() {
        ProfessionalInterval lunch = meal(LocalTime.of(12, 0));
        lunch.setActualEndTime(OffsetDateTime.now());
        when(intervalRepository.findById(lunch.getId())).thenReturn(Optional.of(lunch));

        assertThatThrownBy(() -> intervalService.startInterval(professionalId, lunch.getId()))
                .isInstanceOf(IntervalConflictException.class);
        verify(intervalRepository, never()).save(any());
    }

    @Nested
    @DisplayName("skipMeal")
    class SkipMeal {

        private final LocalDate today = BusinessClock.today();

        @Test
        @DisplayName("pausa avulsa nao se pula -> IntervalConflictException")
        void unexpectedCannotBeSkipped() {
            ProfessionalInterval pausa = unexpected();
            when(intervalRepository.findById(pausa.getId())).thenReturn(Optional.of(pausa));

            assertThatThrownBy(() -> intervalService.skipMeal(professionalId, pausa.getId(), today))
                    .isInstanceOf(IntervalConflictException.class);
        }

        @Test
        @DisplayName("refeicao ja iniciada no dia -> IntervalConflictException")
        void alreadyStarted() {
            ProfessionalInterval lunch = meal(LocalTime.of(12, 0));
            lunch.setIsActive(true);
            lunch.setActualStartTime(OffsetDateTime.now());
            when(intervalRepository.findById(lunch.getId())).thenReturn(Optional.of(lunch));

            assertThatThrownBy(() -> intervalService.skipMeal(professionalId, lunch.getId(), today))
                    .isInstanceOf(IntervalConflictException.class);
        }

        @Test
        @DisplayName("refeicao ja encerrada no dia -> IntervalConflictException")
        void alreadyDone() {
            ProfessionalInterval lunch = meal(LocalTime.of(12, 0));
            lunch.setActualEndTime(OffsetDateTime.now());
            when(intervalRepository.findById(lunch.getId())).thenReturn(Optional.of(lunch));

            assertThatThrownBy(() -> intervalService.skipMeal(professionalId, lunch.getId(), today))
                    .isInstanceOf(IntervalConflictException.class);
        }

        @Test
        @DisplayName("profissional sem loja: pula e devolve establishmentId nulo")
        void withoutEstablishment() {
            ProfessionalInterval lunch = meal(LocalTime.of(12, 0));
            when(intervalRepository.findById(lunch.getId())).thenReturn(Optional.of(lunch));
            saveReturnsArgument();

            var result = intervalService.skipMeal(professionalId, lunch.getId(), today);

            assertThat(result.establishmentId()).isNull();
            assertThat(lunch.isSkippedOn(today)).isTrue();
        }
    }

    @Test
    @DisplayName("encerrar ao atender: so o intervalo iniciado; o apenas previsto fica")
    void finishOnAttend_onlyStarted() {
        ProfessionalInterval previsto = meal(LocalTime.of(18, 0));
        previsto.setIsActive(true);
        ProfessionalInterval semFimPlanejado = meal(LocalTime.of(20, 0));
        semFimPlanejado.setIsActive(true);
        semFimPlanejado.setActualStartTime(OffsetDateTime.now());
        ProfessionalInterval emCurso = meal(LocalTime.of(12, 0));
        emCurso.startInterval();
        when(intervalRepository.findByProfessionalIdAndIsActiveTrue(professionalId))
                .thenReturn(List.of(previsto, semFimPlanejado, emCurso));

        intervalService.finishActiveIntervalsOnAttend(professionalId);

        verify(intervalRepository).save(emCurso);
        verify(intervalRepository, never()).save(previsto);
        verify(intervalRepository, never()).save(semFimPlanejado);
        assertThat(emCurso.getAutoFinishReason()).isEqualTo("ATTEND_NEXT_CLIENT");
    }

    @Test
    @DisplayName("isProfessionalInInterval consulta o repositorio")
    void isProfessionalInInterval() {
        OffsetDateTime at = OffsetDateTime.now();
        when(intervalRepository.existsActiveIntervalAt(professionalId, at)).thenReturn(true);

        assertThat(intervalService.isProfessionalInInterval(professionalId, at)).isTrue();
    }

    @Nested
    @DisplayName("createRecurringInterval — conflito")
    class CreateRecurring {

        private CreateRecurringIntervalCommand command(LocalTime start) {
            return new CreateRecurringIntervalCommand(professionalId, 60, start, "WEEKDAYS", "Almoco");
        }

        @Test
        @DisplayName("sobrepoe uma refeicao ativa -> IntervalConflictException")
        void overlaps() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(intervalRepository.findActiveByProfessionalAndType(professionalId, IntervalType.RECURRENT))
                    .thenReturn(List.of(meal(null), meal(LocalTime.of(12, 30))));

            assertThatThrownBy(() -> intervalService.createRecurringInterval(command(LocalTime.of(12, 0))))
                    .isInstanceOf(IntervalConflictException.class);
            verify(intervalRepository, never()).save(any());
        }

        @Test
        @DisplayName("sem loja e sem sobrepor: cria e publica o evento")
        void createsWithoutEstablishment() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(intervalRepository.findActiveByProfessionalAndType(professionalId, IntervalType.RECURRENT))
                    .thenReturn(List.of(meal(null), meal(LocalTime.of(18, 0))));
            saveReturnsArgument();

            ProfessionalInterval saved = intervalService.createRecurringInterval(command(LocalTime.of(12, 0)));

            assertThat(saved.getFixedStartTime()).isEqualTo(LocalTime.of(12, 0));
            verify(eventPublisher).publishEvent(any(Object.class));
        }
    }

    @Nested
    @DisplayName("createUnexpectedInterval — guardas")
    class CreateUnexpected {

        @Test
        @DisplayName("no passado -> InvalidIntervalTimeException")
        void inThePast() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));

            assertThatThrownBy(() -> intervalService.createUnexpectedInterval(new CreateUnexpectedIntervalCommand(
                    professionalId, 15, OffsetDateTime.now().minusMinutes(5), "Pausa")))
                    .isInstanceOf(InvalidIntervalTimeException.class);
        }

        @Test
        @DisplayName("sobrepoe outra pausa -> IntervalConflictException")
        void overlaps() {
            when(professionalRepository.findById(professionalId)).thenReturn(Optional.of(professional));
            when(intervalRepository.existsUnexpectedIntervalConflict(eq(professionalId), any(), any())).thenReturn(true);

            assertThatThrownBy(() -> intervalService.createUnexpectedInterval(new CreateUnexpectedIntervalCommand(
                    professionalId, 15, OffsetDateTime.now().plusHours(1), "Pausa")))
                    .isInstanceOf(IntervalConflictException.class);
            verify(intervalRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateInterval — edicao parcial")
    class Update {

        @Test
        @DisplayName("so o horario: mantem a duracao")
        void onlyStart() {
            ProfessionalInterval lunch = meal(LocalTime.of(12, 0));
            when(intervalRepository.findById(lunch.getId())).thenReturn(Optional.of(lunch));
            saveReturnsArgument();

            intervalService.updateInterval(professionalId, lunch.getId(), null, LocalTime.of(13, 0), null);

            assertThat(lunch.getFixedStartTime()).isEqualTo(LocalTime.of(13, 0));
            assertThat(lunch.getDurationMinutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("pausa sem horario fixo: so a descricao muda")
        void unexpectedOnlyDescription() {
            ProfessionalInterval pausa = unexpected();
            when(intervalRepository.findById(pausa.getId())).thenReturn(Optional.of(pausa));
            saveReturnsArgument();

            intervalService.updateInterval(professionalId, pausa.getId(), null, null, "Cafe");

            assertThat(pausa.getDescription()).isEqualTo("Cafe");
            assertThat(pausa.getFixedStartTime()).isNull();
        }
    }
}
