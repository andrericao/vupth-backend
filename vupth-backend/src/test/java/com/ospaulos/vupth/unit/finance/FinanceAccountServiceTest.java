package com.ospaulos.vupth.unit.finance;

import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.Account;
import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.Totals;
import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.TotalsReport;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.finance.model.FinanceAccount;
import com.ospaulos.vupth.domain.finance.repository.FinanceAccountRepository;
import com.ospaulos.vupth.domain.finance.service.FinanceAccountService;
import com.ospaulos.vupth.domain.payment.PaymentMethod;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** ledger-por-conta: as contas (Asaas R$ 1,99 no Pix, PJ R$ 0,00), os totais de cada uma e o consolidado. */
class FinanceAccountServiceTest {

    private final FinanceAccountRepository accounts = mock(FinanceAccountRepository.class);
    private final AppLedgerRepository ledger = mock(AppLedgerRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final ReferralCreditRepository referralCredits = mock(ReferralCreditRepository.class);
    private final FinanceAccountService service = new FinanceAccountService(accounts, ledger, attendances, referralCredits);

    private final OffsetDateTime t0 = OffsetDateTime.parse("2026-10-24T10:00:00-03:00");
    private Attendance a, b, c;

    private FinanceAccount account(String key, String fee) {
        FinanceAccount f = new FinanceAccount();
        f.setId(UUID.randomUUID());
        f.setKey(key);
        f.setName(key);
        f.setProvider(key);
        f.setPixFee(new BigDecimal(fee));
        f.setActive(true);
        return f;
    }

    private Attendance attendance(PaymentMethod method, String discount) {
        Attendance x = new Attendance();
        x.setId(UUID.randomUUID());
        x.setPaymentMethod(method);
        ReflectionTestUtils.setField(x, "proReceives", new BigDecimal("30.00"));
        ReflectionTestUtils.setField(x, "discount", new BigDecimal(discount));
        return x;
    }

    private AppLedgerEntry row(Attendance x, String account, String fee, String profit) {
        AppLedgerEntry e = new AppLedgerEntry(x.getId(), Origin.SERVICE, new BigDecimal("32.90"), new BigDecimal(fee),
                new BigDecimal(profit), t0);
        e.setAccountKey(account);
        return e;
    }

    @BeforeEach
    void setUp() {
        when(accounts.findAllByOrderByKeyAsc()).thenReturn(List.of(account("ASAAS", "1.99"), account("PJ", "0.00")));
        a = attendance(PaymentMethod.PIX, "0.00");
        b = attendance(PaymentMethod.PIX, "5.00");
        c = attendance(PaymentMethod.CARTAO, "0.00");
        when(ledger.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any())).thenReturn(List.of(
                row(a, "ASAAS", "2.00", "0.90"), row(b, "ASAAS", "2.00", "0.90"), row(c, "PJ", "2.30", "0.60")));
        when(attendances.findAllById(any())).thenReturn(List.of(a, b, c));
        when(referralCredits.sumByAttendances(Set.of(a.getId(), b.getId()))).thenReturn(new BigDecimal("2.00"));
    }

    @Test
    @DisplayName("a conta do Asaas: tarifa de R$ 1,99 por Pix, o cupom abate a entrada, o profissional recebe o que estipulou e o bonus sai do saldo")
    void asaasAccount() {
        TotalsReport report = service.totals("asaas", t0.minusHours(1), t0.plusHours(1));

        assertThat(report.account()).isEqualTo("ASAAS");
        assertThat(report.byAccount()).isNull();
        Totals t = report.totals();
        assertThat(t.attendances()).isEqualTo(2);
        assertThat(t.pixPayments()).isEqualTo(2);
        assertThat(t.listTotal()).isEqualByComparingTo("65.80");
        assertThat(t.couponDiscount()).isEqualByComparingTo("5.00");
        assertThat(t.entries()).as("o que entrou de fato: corte menos o cupom").isEqualByComparingTo("60.80");
        assertThat(t.fees()).as("2 Pix x R$ 1,99").isEqualByComparingTo("3.98");
        assertThat(t.net()).isEqualByComparingTo("56.82");
        assertThat(t.professionalPart()).isEqualByComparingTo("60.00");
        assertThat(t.appPart()).isEqualByComparingTo("1.80");
        assertThat(t.bonus()).isEqualByComparingTo("2.00");
        assertThat(t.leftover()).as("a taxa do preco (4,00) menos a tarifa paga (3,98)").isEqualByComparingTo("0.02");
        assertThat(t.balance()).as("60,80 - 3,98 - 60,00 - 2,00").isEqualByComparingTo("-5.18");
    }

    @Test
    @DisplayName("a conta PJ: o cartao paga a taxa que o preco carregava; Pix da PJ custa R$ 0,00")
    void pjAccount() {
        Totals t = service.totals("PJ", t0.minusHours(1), t0.plusHours(1)).totals();

        assertThat(t.attendances()).isEqualTo(1);
        assertThat(t.pixPayments()).isZero();
        assertThat(t.otherPayments()).isEqualTo(1);
        assertThat(t.fees()).isEqualByComparingTo("2.30");
        assertThat(t.entries()).isEqualByComparingTo("32.90");
        assertThat(t.leftover()).isEqualByComparingTo("0.00");
        assertThat(t.bonus()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("todas juntas: o consolidado soma as contas e a lista traz cada uma; conta que nao existe, 404")
    void consolidated() {
        TotalsReport report = service.totals("all", t0.minusHours(1), t0.plusHours(1));

        assertThat(report.account()).isEqualTo("all");
        assertThat(report.byAccount()).extracting(Totals::account).containsExactly("ASAAS", "PJ");
        Totals all = report.totals();
        assertThat(all.attendances()).isEqualTo(3);
        assertThat(all.entries()).isEqualByComparingTo("93.70");
        assertThat(all.fees()).isEqualByComparingTo("6.28");
        assertThat(all.professionalPart()).isEqualByComparingTo("90.00");
        assertThat(all.bonus()).isEqualByComparingTo("2.00");
        assertThat(all.balance()).as("93,70 - 6,28 - 90,00 - 2,00").isEqualByComparingTo("-4.58");
        assertThat(service.totals(null, null, null).account()).isEqualTo("all");

        assertThatThrownBy(() -> service.totals("banco-x", t0, t0.plusHours(1))).isInstanceOfSatisfying(AsaasException.class,
                e -> assertThat(e.getCode()).isEqualTo("FINANCE_ACCOUNT_NOT_FOUND"));
    }

    @Test
    @DisplayName("sem movimento: tudo zero, sem consultar a bonificacao; a tarifa de Pix e configuravel por conta")
    void emptyAndPixFee() {
        when(ledger.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());
        when(attendances.findAllById(any())).thenReturn(List.of());
        Totals t = service.totals("ASAAS", t0, t0.plusHours(1)).totals();
        assertThat(t.entries()).isEqualByComparingTo("0");
        assertThat(t.balance()).isEqualByComparingTo("0");

        FinanceAccount asaas = account("ASAAS", "1.99");
        when(accounts.findByKey("ASAAS")).thenReturn(Optional.of(asaas));
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Account updated = service.setPixFee(" asaas ", new BigDecimal("0.99"));
        assertThat(updated.pixFee()).isEqualByComparingTo("0.99");
        assertThatThrownBy(() -> service.setPixFee("nada", BigDecimal.ONE)).isInstanceOf(AsaasException.class);
    }

    @Test
    @DisplayName("a lista de contas traz a tarifa de cada uma")
    void list() {
        List<Account> list = service.list();
        assertThat(list).extracting(Account::key).containsExactly("ASAAS", "PJ");
        assertThat(list.get(0).pixFee()).isEqualByComparingTo("1.99");
        assertThat(list.get(1).pixFee()).isEqualByComparingTo("0.00");
        assertThat(list.get(0).connected()).as("sem a conexao injetada, o campo some").isNull();
    }
}
