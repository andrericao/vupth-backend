package com.ospaulos.vupth.unit.report;

import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.report.dto.response.ProfessionalReportResponse;
import com.ospaulos.vupth.api.report.dto.response.WorkdayResponse;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalShift;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalShiftRepository;
import com.ospaulos.vupth.domain.report.service.impl.ReportServiceImpl;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Expediente real nos relatorios (Partner S4): turnos agrupados por dia da loja. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Relatorios — expediente por dia")
class ReportWorkdaysTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalShiftRepository shiftRepository;

    @InjectMocks ReportServiceImpl reportService;

    private static OffsetDateTime t(String isoLocal) {
        return OffsetDateTime.parse(isoLocal + ":00-03:00");
    }

    private static ProfessionalShift shift(UUID estab, String start, String end) {
        ProfessionalShift s = new ProfessionalShift(UUID.randomUUID(), estab, t(start));
        if (end != null) {
            s.end(t(end));
        }
        return s;
    }

    @Test
    @DisplayName("loja: primeira chegada, ultima saida e a uniao dos turnos; dia seguinte em separado")
    void establishmentWorkdays() {
        UUID ownerId = UUID.randomUUID();
        BaseUser ownerUser = new BaseUser();
        ownerUser.setId(ownerId);
        Owner owner = new Owner(ownerUser);
        Establishment establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
        establishment.setOwner(owner);
        UUID estab = establishment.getId();

        when(establishmentRepository.findById(estab)).thenReturn(Optional.of(establishment));
        when(attendanceRepository.findFinishedByEstablishmentBetween(eq(estab), any(), any())).thenReturn(List.of());
        when(shiftRepository.findByEstablishmentIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                eq(estab), any(), any())).thenReturn(List.of(
                shift(estab, "2026-10-01T08:00", "2026-10-01T12:00"),
                shift(estab, "2026-10-01T09:00", "2026-10-01T10:00"), // dentro do primeiro
                shift(estab, "2026-10-01T11:00", "2026-10-01T18:00"), // sobrepoe o fim do primeiro
                shift(estab, "2026-10-01T19:00", "2026-10-01T20:00"), // depois de um buraco
                shift(estab, "2026-10-02T23:30", "2026-10-03T00:30"))); // vira a meia-noite: conta no dia 02

        EstablishmentReportResponse report = reportService.getEstablishmentReport(ownerId, estab, null, null);

        assertThat(report.workdays()).containsExactly(
                new WorkdayResponse(LocalDate.of(2026, 10, 1), t("2026-10-01T08:00"), t("2026-10-01T20:00"), 660),
                new WorkdayResponse(LocalDate.of(2026, 10, 2), t("2026-10-02T23:30"), t("2026-10-03T00:30"), 60));
    }

    @Test
    @DisplayName("profissional: turno em andamento deixa o fim nulo e conta ate agora")
    void professionalWorkdays_ongoing() {
        BaseUser user = new BaseUser();
        user.setId(UUID.randomUUID());
        user.setFirstName("Carlos");
        Professional professional = new Professional(user);
        UUID pid = professional.getId();
        OffsetDateTime started = OffsetDateTime.now().minusMinutes(90);
        ProfessionalShift open = new ProfessionalShift(pid, null, started);

        when(professionalRepository.findByIdWithBaseUser(pid)).thenReturn(Optional.of(professional));
        when(attendanceRepository.findFinishedByProfessionalBetween(eq(pid), any(), any())).thenReturn(List.of());
        when(shiftRepository.findByProfessionalIdAndStartedAtGreaterThanEqualAndStartedAtLessThanOrderByStartedAtAsc(
                eq(pid), any(), any())).thenReturn(List.of(open));

        ProfessionalReportResponse report = reportService.getProfessionalReport(pid, null, null);

        assertThat(report.workdays()).hasSize(1);
        WorkdayResponse day = report.workdays().get(0);
        assertThat(day.start()).isEqualTo(started);
        assertThat(day.end()).isNull();
        assertThat(day.workedMinutes()).isBetween(89L, 91L);
    }

    @Test
    @DisplayName("sem turno no periodo: lista vazia")
    void professionalWorkdays_empty() {
        BaseUser user = new BaseUser();
        user.setId(UUID.randomUUID());
        Professional professional = new Professional(user);
        when(professionalRepository.findByIdWithBaseUser(professional.getId())).thenReturn(Optional.of(professional));

        ProfessionalReportResponse report = reportService.getProfessionalReport(
                professional.getId(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1));

        assertThat(report.workdays()).isEmpty();
    }
}
