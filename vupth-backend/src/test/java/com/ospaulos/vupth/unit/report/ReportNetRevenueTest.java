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
                .thenReturn(List.of(finished("31.90", "30.00", "30.00"), finished("53.90", "50.00", "50.00"), finished("35.00", null, null)));

        ProfessionalReportResponse report = reportService.getProfessionalReport(professional.getId(), null, null);

        assertThat(report.totalAttendances()).isEqualTo(3);
        assertThat(report.totalRevenue()).isEqualByComparingTo("120.80");
        assertThat(report.netRevenue()).isEqualByComparingTo("80.00");
        assertThat(report.attendancesWithoutSplit()).isEqualTo(1);
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
                .thenReturn(List.of(finished("31.90", "30.00", "30.00"), finished("35.00", null, null)));

        EstablishmentReportResponse report = reportService.getEstablishmentReport(ownerId, establishment.getId(), null, null);

        assertThat(report.netRevenue()).isEqualByComparingTo("30.00");
        assertThat(report.attendancesWithoutSplit()).isEqualTo(1);
        assertThat(report.professionals()).hasSize(1);
        assertThat(report.professionals().get(0).netRevenue()).isEqualByComparingTo("30.00");
        assertThat(report.professionals().get(0).revenue()).isEqualByComparingTo("66.90");
    }
}
