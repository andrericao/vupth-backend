package com.ospaulos.vupth.unit.establishment;

import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentsQueueSummaryUseCase;
import com.ospaulos.vupth.api.establishment.usecase.GetEstablishmentsQueueSummaryUseCase.QueueSummary;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests — GetEstablishmentsQueueSummaryUseCase (B10).
 *
 * Regras cobertas:
 *  - o resumo de cada loja e o MENOR tempo de espera entre os profissionais COM servico (#31),
 *    mais a contagem deles;
 *  - o atendimento em curso entra no tempo, com IN_PROGRESS mandando sobre PAUSED (BUG7);
 *  - loja sem profissional com servico sai com (null, 0) — nao some da lista;
 *  - <b>uma loja quebrada nao derruba as outras</b>: era a propriedade do REQUIRES_NEW do use case
 *    de detalhe, e ela tem de sobreviver a troca pelo lote;
 *  - lista vazia nao consulta o banco (nada de {@code IN ()}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GetEstablishmentsQueueSummaryUseCase")
class GetEstablishmentsQueueSummaryUseCaseTest {

    @Mock ProfessionalRepository professionalRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock ProfessionalIntervalRepository intervalRepository;

    @InjectMocks GetEstablishmentsQueueSummaryUseCase useCase;

    private static final UUID ESTAB_A = UUID.randomUUID();
    private static final UUID ESTAB_B = UUID.randomUUID();
    private static final UUID PROF_1  = UUID.randomUUID();
    private static final UUID PROF_2  = UUID.randomUUID();

    /** Atendimento so com o que a estimativa le: duracao e status. */
    private static Attendance attendance(int minutes, AttendanceStatus status) {
        Attendance a = new Attendance();
        a.setEstimatedMinutes(minutes);
        a.setStatus(status);
        return a;
    }

    @Nested
    @DisplayName("caminho feliz")
    class HappyPath {

        @Test
        @DisplayName("menor espera entre os profissionais da loja + contagem de quem tem servico")
        void minWaitAndCount() {
            when(professionalRepository.findWithServiceByEstablishmentIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{ESTAB_A, PROF_1},
                            new Object[]{ESTAB_A, PROF_2}));
            // PROF_1 com 2 clientes de 30min (=60); PROF_2 com 1 de 10min (=10).
            when(attendanceRepository.findWaitingQueuesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{PROF_1, ESTAB_A, attendance(30, AttendanceStatus.SCHEDULED)},
                            new Object[]{PROF_1, ESTAB_A, attendance(30, AttendanceStatus.SCHEDULED)},
                            new Object[]{PROF_2, ESTAB_A, attendance(10, AttendanceStatus.SCHEDULED)}));
            when(attendanceRepository.findCurrentAttendancesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of());
            when(intervalRepository.findByProfessionalIds(anyCollection())).thenReturn(List.<Object[]>of());

            Map<UUID, QueueSummary> summaries = useCase.execute(List.of(ESTAB_A));

            assertThat(summaries.get(ESTAB_A))
                    .as("a vitrine mostra a fila mais curta da loja, nao a soma")
                    .isEqualTo(new QueueSummary(10, 2));
        }

        @Test
        @DisplayName("o atendimento em curso entra no tempo — IN_PROGRESS manda sobre PAUSED (BUG7)")
        void currentAttendanceCounts() {
            when(professionalRepository.findWithServiceByEstablishmentIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{ESTAB_A, PROF_1}));
            when(attendanceRepository.findWaitingQueuesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of());
            // PAUSED chega primeiro de proposito: a precedencia nao pode depender da ordem das linhas.
            when(attendanceRepository.findCurrentAttendancesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{PROF_1, attendance(45, AttendanceStatus.PAUSED)},
                            new Object[]{PROF_1, attendance(20, AttendanceStatus.IN_PROGRESS)}));
            when(intervalRepository.findByProfessionalIds(anyCollection())).thenReturn(List.<Object[]>of());

            Map<UUID, QueueSummary> summaries = useCase.execute(List.of(ESTAB_A));

            assertThat(summaries.get(ESTAB_A).minWaitMinutes())
                    .as("fila vazia + 20min do atendimento em curso")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("loja sem profissional com servico sai com (null, 0) — nao some da lista")
        void establishmentWithoutProfessionals() {
            when(professionalRepository.findWithServiceByEstablishmentIds(anyCollection()))
                    .thenReturn(List.<Object[]>of());

            Map<UUID, QueueSummary> summaries = useCase.execute(List.of(ESTAB_A, ESTAB_B));

            assertThat(summaries)
                    .containsEntry(ESTAB_A, QueueSummary.EMPTY)
                    .containsEntry(ESTAB_B, QueueSummary.EMPTY);
        }
    }

    @Nested
    @DisplayName("resiliencia e bordas")
    class EdgeCases {

        @Test
        @DisplayName("uma loja quebrada sai com (null, 0) e as outras continuam na lista")
        void brokenEstablishmentDoesNotBreakTheList() {
            Attendance corrompido = mock(Attendance.class);
            when(corrompido.getEstimatedTotalMinutes())
                    .thenThrow(new IllegalStateException("linha corrompida"));

            when(professionalRepository.findWithServiceByEstablishmentIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{ESTAB_A, PROF_1},
                            new Object[]{ESTAB_B, PROF_2}));
            when(attendanceRepository.findWaitingQueuesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{PROF_1, ESTAB_A, corrompido},
                            new Object[]{PROF_2, ESTAB_B, attendance(15, AttendanceStatus.SCHEDULED)}));
            when(attendanceRepository.findCurrentAttendancesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of());
            when(intervalRepository.findByProfessionalIds(anyCollection())).thenReturn(List.<Object[]>of());

            Map<UUID, QueueSummary> summaries = useCase.execute(List.of(ESTAB_A, ESTAB_B));

            assertThat(summaries.get(ESTAB_A))
                    .as("a loja que falhou sai sem tempo, nao com 500")
                    .isEqualTo(QueueSummary.EMPTY);
            assertThat(summaries.get(ESTAB_B))
                    .as("a lista segue inteira")
                    .isEqualTo(new QueueSummary(15, 1));
        }

        @Test
        @DisplayName("a fila de outra loja do mesmo profissional nao entra no resumo")
        void queueIsScopedByProfessionalAndEstablishment() {
            when(professionalRepository.findWithServiceByEstablishmentIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(new Object[]{ESTAB_A, PROF_1}));
            when(attendanceRepository.findWaitingQueuesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of(
                            new Object[]{PROF_1, ESTAB_B, attendance(90, AttendanceStatus.SCHEDULED)}));
            when(attendanceRepository.findCurrentAttendancesByProfessionalIds(anyCollection()))
                    .thenReturn(List.<Object[]>of());
            when(intervalRepository.findByProfessionalIds(anyCollection())).thenReturn(List.<Object[]>of());

            Map<UUID, QueueSummary> summaries = useCase.execute(List.of(ESTAB_A));

            assertThat(summaries.get(ESTAB_A).minWaitMinutes())
                    .as("a fila e do par profissional+loja, como na consulta que este lote substituiu")
                    .isZero();
        }

        @Test
        @DisplayName("lista vazia nao consulta o banco")
        void emptyInputHitsNoRepository() {
            assertThat(useCase.execute(List.of())).isEmpty();
            assertThat(useCase.execute(null)).isEmpty();

            verifyNoInteractions(professionalRepository, attendanceRepository, intervalRepository);
        }
    }
}
