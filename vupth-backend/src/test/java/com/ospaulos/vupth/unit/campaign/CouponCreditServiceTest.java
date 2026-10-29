package com.ospaulos.vupth.unit.campaign;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.event.AttendanceFinishedEvent;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.model.CouponCredit;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.repository.CouponCreditRepository;
import com.ospaulos.vupth.domain.campaign.service.CouponCreditService;
import com.ospaulos.vupth.domain.professional.model.Professional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** O desconto do cupom vira credito do profissional so quando o corte termina, uma vez, e nunca no teste. */
class CouponCreditServiceTest {

    private final CouponCreditRepository credits = mock(CouponCreditRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CouponCreditService service = new CouponCreditService(credits, attendances, campaigns);

    private Attendance attendance(AttendanceStatus status, String discount, boolean coupon, boolean test) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setStatus(status);
        a.setDiscount(new BigDecimal(discount));
        a.setCouponCampaignId(coupon ? UUID.randomUUID() : null);
        a.setTest(test);
        Professional p = mock(Professional.class);
        when(p.getId()).thenReturn(UUID.randomUUID());
        a.setProfessional(p);
        when(attendances.findByIdEager(a.getId())).thenReturn(Optional.of(a));
        return a;
    }

    @Test
    @DisplayName("concluido com cupom: credita o desconto ao profissional")
    void credits() {
        Attendance a = attendance(AttendanceStatus.FINISHED, "5.00", true, false);
        service.credit(a.getId());
        verify(credits).save(org.mockito.ArgumentMatchers.<CouponCredit>argThat(c ->
                c.getAmount().compareTo(new BigDecimal("5.00")) == 0
                        && c.getBeneficiaryId().equals(a.getProfessional().getId())
                        && c.getAttendanceId().equals(a.getId())));
    }

    @Test
    @DisplayName("DEC-127: com divisao, o app repassa a diferenca (R$ 30,00 − R$ 22,90 = R$ 7,10), nao o desconto inteiro")
    void creditsTheDifference() {
        Attendance a = attendance(AttendanceStatus.FINISHED, "10.00", true, false);
        a.setTotalValue(new BigDecimal("32.90"));
        a.setProReceives(new BigDecimal("30.00"));
        service.credit(a.getId());
        verify(credits).save(org.mockito.ArgumentMatchers.<CouponCredit>argThat(c -> c.getAmount().compareTo(new BigDecimal("7.10")) == 0));

        Attendance small = attendance(AttendanceStatus.FINISHED, "2.00", true, false);
        small.setTotalValue(new BigDecimal("32.90"));
        small.setProReceives(new BigDecimal("30.00"));
        service.credit(small.getId()); // 30,90 ja cobre os 30,00: a tarifa absorve, nada a repassar
        verify(credits, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("nao credita: sem concluir, sem cupom, sem desconto, em teste, ja creditado ou atendimento inexistente")
    void doesNotCredit() {
        service.credit(attendance(AttendanceStatus.IN_PROGRESS, "5.00", true, false).getId());
        service.credit(attendance(AttendanceStatus.FINISHED, "5.00", false, false).getId());
        service.credit(attendance(AttendanceStatus.FINISHED, "0.00", true, false).getId());
        service.credit(attendance(AttendanceStatus.FINISHED, "5.00", true, true).getId());
        Attendance done = attendance(AttendanceStatus.FINISHED, "5.00", true, false);
        when(credits.existsByAttendanceId(done.getId())).thenReturn(true);
        service.credit(done.getId());
        service.credit(UUID.randomUUID());
        verify(credits, never()).save(any());
    }

    @Test
    @DisplayName("o evento de fim de atendimento credita; uma falha so e logada")
    void eventNeverBreaks() {
        Attendance a = attendance(AttendanceStatus.FINISHED, "5.00", true, false);
        service.onFinished(new AttendanceFinishedEvent(a.getId()));
        verify(credits).save(any());

        Attendance broken = attendance(AttendanceStatus.FINISHED, "5.00", true, false);
        when(credits.existsByAttendanceId(broken.getId())).thenThrow(new IllegalStateException("banco fora"));
        service.onFinished(new AttendanceFinishedEvent(broken.getId()));
        assertThat(true).isTrue();
    }
}
