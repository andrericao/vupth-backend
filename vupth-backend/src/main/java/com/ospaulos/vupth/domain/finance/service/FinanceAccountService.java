package com.ospaulos.vupth.domain.finance.service;

import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.Account;
import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.Totals;
import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.TotalsReport;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Window;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.finance.model.FinanceAccount;
import com.ospaulos.vupth.domain.finance.repository.FinanceAccountRepository;
import com.ospaulos.vupth.domain.payment.PaymentMethod;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.payment.service.PaymentGatewayService;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * As contas por onde o dinheiro passa e os totais de cada uma e de todas juntas (ledger-por-conta, 24/10). Le o livro do app
 * ({@link AppLedgerRepository}, com a conta de cada linha), os atendimentos e a bonificacao; nada e gravado aqui, so a tarifa
 * de Pix de cada conta, que o Painel muda.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class FinanceAccountService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final String ALL = "all";

    private final FinanceAccountRepository accounts;
    private final AppLedgerRepository ledger;
    private final AttendanceRepository attendances;
    private final ReferralCreditRepository referralCredits;

    /** Para dizer, na conta do Asaas, se a conexao esta ligada; sem ele (testes de unidade) o campo some. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PaymentGatewayService gateway;

    @Transactional(readOnly = true)
    public List<Account> list() {
        return accounts.findAllByOrderByKeyAsc().stream().map(this::toDto).toList();
    }

    public Account setPixFee(String key, BigDecimal pixFee) {
        FinanceAccount account = accounts.findByKey(key.trim().toUpperCase(Locale.ROOT)).orElseThrow(FinanceAccountService::notFound);
        account.setPixFee(pixFee.setScale(2, java.math.RoundingMode.HALF_UP));
        return toDto(accounts.save(account));
    }

    /** Os totais de uma conta ({@code key} ou id) ou de todas ({@code all} ou vazio) no periodo; sem datas: de hoje ate agora. */
    @Transactional(readOnly = true)
    public TotalsReport totals(String account, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to != null ? to : OffsetDateTime.now();
        OffsetDateTime start = from != null ? from : BusinessClock.startOfDay(BusinessClock.dateAt(end));
        List<FinanceAccount> all = accounts.findAllByOrderByKeyAsc();
        List<AppLedgerEntry> entries = ledger.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(start, end);
        Map<UUID, Attendance> byId = attendances.findAllById(
                entries.stream().map(AppLedgerEntry::getAttendanceId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Attendance::getId, a -> a));
        Map<String, BigDecimal> pixFees = all.stream().collect(Collectors.toMap(FinanceAccount::getKey, FinanceAccount::getPixFee));

        boolean everything = account == null || account.isBlank() || ALL.equalsIgnoreCase(account.trim());
        if (everything) {
            Map<String, List<AppLedgerEntry>> grouped = new LinkedHashMap<>();
            all.forEach(a -> grouped.put(a.getKey(), new ArrayList<>()));
            entries.forEach(e -> grouped.computeIfAbsent(e.getAccountKey(), k -> new ArrayList<>()).add(e));
            List<Totals> per = grouped.entrySet().stream()
                    .map(g -> compute(g.getKey(), g.getValue(), byId, pixFees.getOrDefault(g.getKey(), ZERO))).toList();
            return new TotalsReport(new Window(start, end), ALL, consolidate(per), per);
        }
        FinanceAccount found = find(account, all);
        List<AppLedgerEntry> mine = entries.stream().filter(e -> found.getKey().equals(e.getAccountKey())).toList();
        return new TotalsReport(new Window(start, end), found.getKey(),
                compute(found.getKey(), mine, byId, found.getPixFee()), null);
    }

    private Totals compute(String key, List<AppLedgerEntry> rows, Map<UUID, Attendance> byId, BigDecimal pixFee) {
        Set<UUID> ids = rows.stream().map(AppLedgerEntry::getAttendanceId).collect(Collectors.toSet());
        BigDecimal listTotal = ZERO, charged = ZERO, appPart = ZERO, cardFees = ZERO;
        long withoutSplit = 0;
        for (AppLedgerEntry e : rows) {
            listTotal = listTotal.add(e.getAmount());
            charged = charged.add(e.getGatewayFee());
            appPart = appPart.add(nz(e.getAppProfit()));
            if (e.getOrigin() == AppLedgerEntry.Origin.SERVICE && e.getAppProfit() == null) {
                withoutSplit++;
            }
            Attendance a = byId.get(e.getAttendanceId());
            if (a != null && a.getPaymentMethod() == PaymentMethod.CARTAO) {
                cardFees = cardFees.add(e.getGatewayFee()); // o cartao paga a taxa que o preco ja carregava
            }
        }
        BigDecimal discount = ZERO, professional = ZERO;
        long pix = 0, other = 0;
        for (UUID id : ids) {
            Attendance a = byId.get(id);
            if (a == null) {
                continue;
            }
            discount = discount.add(nz(a.getDiscount()));
            professional = professional.add(nz(a.getProReceives()));
            if (a.getPaymentMethod() == null || a.getPaymentMethod() == PaymentMethod.PIX) {
                pix++;
            } else {
                other++;
            }
        }
        BigDecimal fees = pixFee.multiply(BigDecimal.valueOf(pix)).add(cardFees);
        BigDecimal bonus = ids.isEmpty() ? ZERO : nz(referralCredits.sumByAttendances(ids));
        BigDecimal entries = listTotal.subtract(discount);
        return new Totals(key, ids.size(), pix, other, listTotal, discount, entries, fees, entries.subtract(fees), professional,
                appPart, bonus, charged.subtract(fees), entries.subtract(fees).subtract(professional).subtract(bonus), withoutSplit);
    }

    private static Totals consolidate(List<Totals> parts) {
        BigDecimal[] sum = new BigDecimal[9];
        java.util.Arrays.fill(sum, ZERO);
        long attendances = 0, pix = 0, other = 0, without = 0;
        for (Totals t : parts) {
            attendances += t.attendances();
            pix += t.pixPayments();
            other += t.otherPayments();
            without += t.servicesWithoutSplit();
            BigDecimal[] v = {t.listTotal(), t.couponDiscount(), t.entries(), t.fees(), t.net(), t.professionalPart(), t.appPart(),
                    t.bonus(), t.leftover()};
            for (int i = 0; i < v.length; i++) {
                sum[i] = sum[i].add(v[i]);
            }
        }
        BigDecimal balance = sum[2].subtract(sum[3]).subtract(sum[5]).subtract(sum[7]);
        return new Totals(ALL, attendances, pix, other, sum[0], sum[1], sum[2], sum[3], sum[4], sum[5], sum[6], sum[7], sum[8],
                balance, without);
    }

    private static FinanceAccount find(String account, List<FinanceAccount> all) {
        String wanted = account.trim();
        return all.stream().filter(a -> a.getKey().equalsIgnoreCase(wanted) || a.getId().toString().equalsIgnoreCase(wanted))
                .findFirst().orElseThrow(FinanceAccountService::notFound);
    }

    private Account toDto(FinanceAccount a) {
        boolean asaas = FinanceAccount.ASAAS.equals(a.getKey()) && gateway != null;
        return new Account(a.getId(), a.getKey(), a.getName(), a.getProvider(), a.getPixFee(), a.isActive(),
                asaas ? gateway.connected() : null, asaas && gateway.connected() ? gateway.mode() : null);
    }

    private static AsaasException notFound() {
        return new AsaasException(HttpStatus.NOT_FOUND, "FINANCE_ACCOUNT_NOT_FOUND", "Conta nao encontrada.");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }
}
