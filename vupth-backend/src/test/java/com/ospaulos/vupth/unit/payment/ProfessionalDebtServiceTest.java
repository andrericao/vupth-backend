package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.debt.model.DebtStatus;
import com.ospaulos.vupth.domain.debt.model.ProfessionalDebt;
import com.ospaulos.vupth.domain.debt.repository.ProfessionalDebtRepository;
import com.ospaulos.vupth.domain.debt.service.ProfessionalDebtService;
import com.ospaulos.vupth.domain.payment.PaymentMethod;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.config.AppPix;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A divida do pagamento por fora: quando nasce, quando e simulada, o limite de R$ 25 e o teto do abatimento (50%). */
class ProfessionalDebtServiceTest {

    private final ProfessionalDebtRepository repository = mock(ProfessionalDebtRepository.class);
    private final AppSettingService settings = mock(AppSettingService.class);
    private final AppPix appPix = new AppPix("+5511999999999", "VUPTH", "SAO PAULO");
    private final ProfessionalDebtService service = new ProfessionalDebtService(repository, settings, appPix);
    private final UUID professionalId = UUID.randomUUID();

    private Attendance attendance(String total, String proReceives, boolean test) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        a.setTotalValue(new BigDecimal(total));
        a.setTest(test);
        ReflectionTestUtils.setField(a, "proReceives", proReceives == null ? null : new BigDecimal(proReceives));
        Professional p = mock(Professional.class);
        when(p.getId()).thenReturn(professionalId);
        a.setProfessional(p);
        return a;
    }

    @Test
    @DisplayName("por fora grava a divida inteira (R$ 32,90 - R$ 30,00 = R$ 2,90), simulada com a flag desligada e real com ela ligada")
    void recordsWholeDifference() {
        when(settings.flag(AppSettingService.DEBT_REAL, false)).thenReturn(false);
        service.recordOnReceived(attendance("32.90", "30.00", false), PaymentMethod.CARTAO);
        verify(repository).save(org.mockito.ArgumentMatchers.<ProfessionalDebt>argThat(d ->
                d.getAmount().compareTo(new BigDecimal("2.90")) == 0 && d.isSimulated() && d.getStatus() == DebtStatus.OPEN));

        when(settings.flag(AppSettingService.DEBT_REAL, false)).thenReturn(true);
        service.recordOnReceived(attendance("32.90", "30.00", false), PaymentMethod.NOTA);
        verify(repository).save(org.mockito.ArgumentMatchers.<ProfessionalDebt>argThat(d -> !d.isSimulated()));
    }

    @Test
    @DisplayName("DEC-127: com cupom a divida e o que passa do estipulado, nao o preco cheio (R$ 22,90 - R$ 30,00 = nada; o app repassa)")
    void discountIsTheAppsNotTheProfessionals() {
        Attendance discounted = attendance("32.90", "30.00", false);
        discounted.setDiscount(new BigDecimal("10.00"));
        service.recordOnReceived(discounted, PaymentMethod.CARTAO);
        verify(repository, never()).save(any());

        Attendance small = attendance("32.90", "30.00", false);
        small.setDiscount(new BigDecimal("1.00"));
        service.recordOnReceived(small, PaymentMethod.CARTAO);
        verify(repository).save(org.mockito.ArgumentMatchers.<ProfessionalDebt>argThat(d -> d.getAmount().compareTo(new BigDecimal("1.90")) == 0));
    }

    @Test
    @DisplayName("DEC-127: cliente com cupom de R$ 10,00 que pagou o cheio (R$ 32,90) ao profissional: a divida desfaz o repasse (R$ 10,00)")
    void paidFullDespiteDiscount() {
        Attendance full = attendance("32.90", "30.00", false);
        full.setDiscount(new BigDecimal("10.00"));
        full.setReceivedAmount(new BigDecimal("32.90"));
        service.recordOnReceived(full, PaymentMethod.CARTAO);
        verify(repository).save(org.mockito.ArgumentMatchers.<ProfessionalDebt>argThat(d -> d.getAmount().compareTo(new BigDecimal("10.00")) == 0));
    }

    @Test
    @DisplayName("nao grava: Pix, sem forma, teste, servico legado, sem diferenca ou corte ja registrado")
    void doesNotRecord() {
        service.recordOnReceived(attendance("32.90", "30.00", false), PaymentMethod.PIX);
        service.recordOnReceived(attendance("32.90", "30.00", false), null);
        service.recordOnReceived(attendance("32.90", "30.00", true), PaymentMethod.CARTAO);
        service.recordOnReceived(attendance("32.90", null, false), PaymentMethod.CARTAO);
        service.recordOnReceived(attendance("30.00", "30.00", false), PaymentMethod.CARTAO);
        Attendance done = attendance("32.90", "30.00", false);
        when(repository.existsByAttendanceId(done.getId())).thenReturn(true);
        service.recordOnReceived(done, PaymentMethod.CARTAO);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a divida real a partir de R$ 25 avisa e traz o Pix de cobranca; a simulada nunca conta")
    void limit() {
        when(repository.sumOpen(professionalId, false)).thenReturn(new BigDecimal("24.90"));
        when(repository.sumOpen(professionalId, true)).thenReturn(new BigDecimal("80.00"));
        when(repository.findByProfessionalIdOrderByCreatedAtDesc(professionalId)).thenReturn(List.of());
        var under = service.debtOf(professionalId);
        assertThat(under.overLimit()).isFalse();
        assertThat(under.simulatedAmount()).isEqualByComparingTo("80.00");
        assertThat(under.pixCopyPaste()).isNotNull();

        when(repository.sumOpen(professionalId, false)).thenReturn(new BigDecimal("25.00"));
        assertThat(service.debtOf(professionalId).overLimit()).isTrue();

        when(repository.sumOpen(professionalId, false)).thenReturn(BigDecimal.ZERO);
        var none = service.debtOf(professionalId);
        assertThat(none.overLimit()).isFalse();
        assertThat(none.pixCopyPaste()).isNull();
    }

    @Test
    @DisplayName("quitar marca as reais em aberto e soma; abatimento: no maximo 50% do que o profissional recebe, nunca mais que a divida")
    void settleAndAbatement() {
        ProfessionalDebt a = new ProfessionalDebt(professionalId, UUID.randomUUID(), new BigDecimal("2.90"),
                com.ospaulos.vupth.domain.debt.model.DebtOrigin.PAID_OUTSIDE, false, java.time.OffsetDateTime.now());
        ProfessionalDebt b = new ProfessionalDebt(professionalId, UUID.randomUUID(), new BigDecimal("2.90"),
                com.ospaulos.vupth.domain.debt.model.DebtOrigin.PAID_OUTSIDE, false, java.time.OffsetDateTime.now());
        when(repository.findByProfessionalIdAndStatusAndSimulated(professionalId, DebtStatus.OPEN, false))
                .thenReturn(List.of(a, b));
        assertThat(service.settle(professionalId)).isEqualByComparingTo("5.80");
        assertThat(a.getStatus()).isEqualTo(DebtStatus.SETTLED);
        assertThat(b.getSettledAt()).isNotNull();

        assertThat(ProfessionalDebtService.abatement(new BigDecimal("27.10"), new BigDecimal("2.90"))).isEqualByComparingTo("2.90");
        assertThat(ProfessionalDebtService.abatement(new BigDecimal("10.00"), new BigDecimal("30.00"))).isEqualByComparingTo("5.00");
        assertThat(ProfessionalDebtService.abatement(new BigDecimal("10.00"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("o Painel lista as dividas em aberto, reais e simuladas")
    void rows() {
        UUID p = UUID.randomUUID();
        when(repository.openByProfessional(false)).thenReturn(List.<Object[]>of(new Object[]{p, new BigDecimal("5.80")}));
        when(repository.openByProfessional(true)).thenReturn(List.<Object[]>of(new Object[]{p, new BigDecimal("2.90")}));
        var rows = service.openRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).simulated()).isFalse();
        assertThat(rows.get(1).simulated()).isTrue();
        when(settings.flag(AppSettingService.DEBT_REAL, false)).thenReturn(true);
        assertThat(service.isReal()).isTrue();
    }
}
