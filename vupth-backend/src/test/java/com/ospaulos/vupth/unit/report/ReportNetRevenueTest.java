package com.ospaulos.vupth.unit.report;

import com.ospaulos.vupth.api.report.dto.response.EstablishmentReportResponse;
import com.ospaulos.vupth.api.report.dto.response.ProfessionalReportResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalShiftRepository;
import com.ospaulos.vupth.domain.report.service.impl.ReportServiceImpl;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests — relatorios com a Receita LIQUIDA.
 *
 * netRevenue soma o que o profissional recebe nos atendimentos que TEM divisao congelada;
 * attendancesWithoutSplit conta os que nao tem (servico legado / anteriores a 16/09), para o app
 * avisar que o liquido esta incompleto em vez de mostrar um numero menor como se fosse o todo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Relatorios — receita liquida")
class ReportNetRevenueTest {

    @Mock AttendanceRepository attendanceRepository;
    @Mock ProfessionalRepository professionalRepository;
    @Mock EstablishmentRepository establishmentRepository;
    @Mock ProfessionalShiftRepository shiftRepository;

    @Mock com.ospaulos.vupth.domain.debt.repository.ProfessionalDebtRepository debtRepository;

    @InjectMocks ReportServiceImpl reportService;

    private Professional professional;
    private Establishment establishment;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        BaseUser user = new BaseUser();
        user.setId(UUID.randomUUID());
        user.setFirstName("Carlos");
        professional = new Professional(user);
        ownerId = user.getId();

        establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
    }

    private Attendance finished(String price, String proReceives, String base) {
        ServiceItem item = new ServiceItem();
        item.setServiceItemType(ServiceItemType.CORTE_MAQUINA);
        item.setEstimatedMinutes(30);
        item.reprice(new BigDecimal(price),
                proReceives == null ? null : new BigDecimal(proReceives),
                base == null ? null : new BigDecimal(base));

        Attendance attendance = new Attendance();
        attendance.setProfessional(professional);
        attendance.setServiceItems(new ArrayList<>(List.of(item)));
        attendance.onCreate();
        return attendance;
    }

    @Test
    @DisplayName("profissional: soma o liquido de quem tem divisao e conta quem nao tem")
    void professionalReport_netRevenueAndWithoutSplit() {
        when(professionalRepository.findByIdWithBaseUser(professional.getId())).thenReturn(Optional.of(professional));
        when(attendanceRepository.findFinishedByProfessionalBetween(eq(professional.getId()), any(), any()))
                .thenReturn(List.of(finished("32.90", "30.00", "30.00"), finished("53.90", "50.00", "50.00"), finished("35.00", null, null)));

        ProfessionalReportResponse report = reportService.getProfessionalReport(professional.getId(), null, null);

        assertThat(report.totalAttendances()).isEqualTo(3);
        assertThat(report.totalRevenue()).isEqualByComparingTo("121.80");
        assertThat(report.netRevenue()).isEqualByComparingTo("80.00");
        assertThat(report.attendancesWithoutSplit()).isEqualTo(1);
    }

    @Test
    @DisplayName("PAR-3: recebe e taxas por corte e no periodo; divida por fora real aberta em pending_debt, a simulada a parte")
    void professionalReport_receivesFeesAndPendingDebt() {
        Attendance a = finished("32.90", "30.00", "30.00");
        Attendance b = finished("53.90", "50.00", "50.00");
        Attendance legacy = finished("35.00", null, null);
        for (Attendance x : List.of(a, b, legacy)) {
            org.springframework.test.util.ReflectionTestUtils.setField(x, "id", UUID.randomUUID());
        }
        when(professionalRepository.findByIdWithBaseUser(professional.getId())).thenReturn(Optional.of(professional));
        when(attendanceRepository.findFinishedByProfessionalBetween(eq(professional.getId()), any(), any()))
                .thenReturn(List.of(a, b, legacy));
        var open = new com.ospaulos.vupth.domain.debt.model.ProfessionalDebt(professional.getId(), a.getId(), new BigDecimal("2.90"),
                com.ospaulos.vupth.domain.debt.model.DebtOrigin.PAID_OUTSIDE, false, java.time.OffsetDateTime.now());
        var simulated = new com.ospaulos.vupth.domain.debt.model.ProfessionalDebt(professional.getId(), b.getId(), new BigDecimal("2.90"),
                com.ospaulos.vupth.domain.debt.model.DebtOrigin.PAID_OUTSIDE, true, java.time.OffsetDateTime.now());
        var settled = new com.ospaulos.vupth.domain.debt.model.ProfessionalDebt(professional.getId(), legacy.getId(), new BigDecimal("2.90"),
                com.ospaulos.vupth.domain.debt.model.DebtOrigin.PAID_OUTSIDE, false, java.time.OffsetDateTime.now());
        settled.setStatus(com.ospaulos.vupth.domain.debt.model.DebtStatus.SETTLED);
        when(debtRepository.findByAttendanceIdIn(any())).thenReturn(List.of(open, simulated, settled));

        ProfessionalReportResponse report = reportService.getProfessionalReport(professional.getId(), null, null);

        assertThat(report.receivesTotal()).isEqualByComparingTo("80.00");
        assertThat(report.feesTotal()).isEqualByComparingTo("6.80");
        assertThat(report.pendingDebtTotal()).isEqualByComparingTo("2.90");
        assertThat(report.pendingDebtSimulatedTotal()).isEqualByComparingTo("2.90");
        assertThat(report.cuts()).hasSize(3);
        var first = report.cuts().get(0);
        assertThat(first.attendanceId()).isEqualTo(a.getId());
        assertThat(first.receives()).isEqualByComparingTo("30.00");
        assertThat(first.fees()).isEqualByComparingTo("2.90");
        assertThat(first.pendingDebt()).isEqualByComparingTo("2.90");
        assertThat(report.cuts().get(1).pendingDebtSimulated()).isEqualByComparingTo("2.90");
        var legacyCut = report.cuts().get(2);
        assertThat(legacyCut.receives()).isNull();
        assertThat(legacyCut.fees()).isNull();
        assertThat(legacyCut.pendingDebt()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("profissional sem atendimentos: liquido zero, nenhum sem divisao")
    void professionalReport_empty() {
        when(professionalRepository.findByIdWithBaseUser(professional.getId())).thenReturn(Optional.of(professional));
        when(attendanceRepository.findFinishedByProfessionalBetween(eq(professional.getId()), any(), any()))
                .thenReturn(List.of());

        ProfessionalReportResponse report = reportService.getProfessionalReport(professional.getId(), null, null);

        assertThat(report.netRevenue()).isEqualByComparingTo("0");
        assertThat(report.attendancesWithoutSplit()).isZero();
    }

    @Test
    @DisplayName("loja: liquido total e por profissional, e a contagem dos sem divisao")
    void establishmentReport_netRevenue() {
        BaseUser owner = new BaseUser();
        owner.setId(ownerId);
        establishment.setOwner(new Owner(owner));
        when(establishmentRepository.findById(establishment.getId())).thenReturn(Optional.of(establishment));
        when(attendanceRepository.findFinishedByEstablishmentBetween(eq(establishment.getId()), any(), any()))
                .thenReturn(List.of(finished("32.90", "30.00", "30.00"), finished("35.00", null, null)));

        EstablishmentReportResponse report = reportService.getEstablishmentReport(ownerId, establishment.getId(), null, null);

        assertThat(report.netRevenue()).isEqualByComparingTo("30.00");
        assertThat(report.attendancesWithoutSplit()).isEqualTo(1);
        assertThat(report.professionals()).hasSize(1);
        assertThat(report.professionals().get(0).netRevenue()).isEqualByComparingTo("30.00");
        assertThat(report.professionals().get(0).revenue()).isEqualByComparingTo("67.90");
    }
}
