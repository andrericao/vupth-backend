package com.ospaulos.vupth.domain.referral.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.referral.dto.AdminReferralPayoutResponse;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * saldo-do-app-e-bonificacao (DEC-120): o desconto (cupom, link, bonificacao) e pago pelo Andre. Aqui so se CALCULA e se mostra:
 * quanto cada pessoa tem a receber (premio liberado e ainda nao pago), o saldo devedor (a soma de todas), o saldo do app (o
 * disponivel na conta Asaas) e, se o saldo nao cobre, a diferenca que o Andre deposita a mao. Sem conexao com o Asaas, so o devedor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppBalanceService {

    private final AdminReferralService referrals;
    private final AsaasClient asaas;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Beneficiary(
            @JsonProperty("beneficiary_id") UUID beneficiaryId,
            @JsonProperty("beneficiary_name") String beneficiaryName,
            @JsonProperty("pix_key_type") String pixKeyType,
            @JsonProperty("pix_key") String pixKey,
            @JsonProperty("pix_holder_name") String pixHolderName,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("credits") long credits) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Balance(
            @JsonProperty("owed_total") BigDecimal owedTotal,
            @JsonProperty("pending_release_total") BigDecimal pendingReleaseTotal,
            @JsonProperty("beneficiaries") List<Beneficiary> beneficiaries,
            @JsonProperty("asaas_connected") boolean asaasConnected,
            @JsonProperty("app_balance") BigDecimal appBalance,
            @JsonProperty("deposit_needed") BigDecimal depositNeeded,
            @JsonProperty("asaas_error") String asaasError) {
    }

    public Balance balance() {
        Map<UUID, Beneficiary> byPerson = new LinkedHashMap<>();
        for (AdminReferralPayoutResponse row : referrals.payouts()) {
            byPerson.merge(row.beneficiaryId(),
                    new Beneficiary(row.beneficiaryId(), row.beneficiaryName(), row.pixKeyType(), row.pixKey(), row.pixHolderName(),
                            row.amount(), row.credits()),
                    (a, b) -> new Beneficiary(a.beneficiaryId(), a.beneficiaryName(), a.pixKeyType(), a.pixKey(), a.pixHolderName(),
                            a.amount().add(b.amount()), a.credits() + b.credits()));
        }
        List<Beneficiary> people = new ArrayList<>(byPerson.values());
        people.sort(Comparator.comparing(Beneficiary::amount).reversed());
        BigDecimal owed = people.stream().map(Beneficiary::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = referrals.pendingRelease().stream().map(AdminReferralPayoutResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (!asaas.enabled()) {
            return new Balance(owed, pending, people, false, null, null, null);
        }
        try {
            BigDecimal available = asaas.balance();
            BigDecimal gap = owed.subtract(available).max(BigDecimal.ZERO);
            return new Balance(owed, pending, people, true, available, gap, null);
        } catch (AsaasException e) {
            log.warn("[Balance] saldo do Asaas indisponivel: {}", e.getMessage());
            return new Balance(owed, pending, people, true, null, null, e.getCode());
        }
    }
}
