package com.ospaulos.vupth.domain.payment.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.payment.model.PaymentAccount;
import com.ospaulos.vupth.domain.payment.repository.PaymentAccountRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A conta de recebimento do profissional no Asaas (gateway-de-pagamento, fatia 1). Hoje so a <b>subconta de teste</b> do sandbox,
 * com dados ficticios validos e o aceite marcado como teste; no modo real a abertura fica recusada ate o aceite real, o CPF/CNPJ do
 * profissional e a verificacao (fatia 4). Cada profissional tem uma; abrir de novo devolve a mesma.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentAccountService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PaymentAccountRepository accounts;
    private final UserRepository users;
    private final AsaasClient client;
    private final AsaasProperties properties;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record State(
            @JsonProperty("status") String status,
            @JsonProperty("mode") String mode,
            @JsonProperty("test") boolean test,
            @JsonProperty("accept_version") String acceptVersion,
            @JsonProperty("accepted_at") OffsetDateTime acceptedAt) {}

    public State open(UUID userId, String acceptVersion, String ip) {
        Optional<PaymentAccount> existing = accounts.findById(userId);
        if (existing.isPresent()) {
            return toState(existing.get());
        }
        if (!properties.sandbox()) {
            // o real pede o aceite real, o CPF/CNPJ do profissional e a verificacao da conta: ainda nao (fatia 4)
            throw new AsaasException(HttpStatus.CONFLICT, "PAYMENT_ACCOUNT_REAL_NOT_READY",
                    "A conta de recebimento real ainda nao esta disponivel.");
        }
        BaseUser user = users.findById(userId).orElseThrow(() -> new AsaasException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND",
                "Usuario nao encontrado."));
        AsaasClient.Subaccount sub = client.createSubaccount(testSubaccountBody(user));
        PaymentAccount saved = accounts.save(new PaymentAccount(userId, sub.id(), sub.walletId(), properties.mode(), acceptVersion,
                OffsetDateTime.now(), ip, true));
        log.info("[PaymentAccount] subconta de teste aberta — user={} modo={}", userId, saved.getMode());
        return toState(saved);
    }

    @Transactional(readOnly = true)
    public Optional<State> state(UUID userId) {
        return accounts.findById(userId).map(PaymentAccountService::toState);
    }

    /** A carteira que recebe a divisao, se o profissional tem conta ativa no mesmo modo da cobranca. */
    @Transactional(readOnly = true)
    public Optional<String> walletFor(UUID professionalId, String mode) {
        return accounts.findById(professionalId)
                .filter(a -> PaymentAccount.ACTIVE.equals(a.getStatus()) && a.getMode().equals(mode))
                .map(PaymentAccount::getWalletId);
    }

    private static State toState(PaymentAccount a) {
        return new State(a.getStatus(), a.getMode(), a.isTest(), a.getAcceptVersion(), a.getAcceptedAt());
    }

    /** Dados ficticios validos: so o nome e o e-mail (com a marca de teste, unico por pessoa) vem do cadastro. */
    static Map<String, Object> testSubaccountBody(BaseUser user) {
        String name = (user.getFirstName() == null ? "Profissional" : user.getFirstName()) + " Teste VUPTH";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("email", "teste+" + user.getId().toString().substring(0, 8) + "@vupth.test");
        body.put("cpfCnpj", randomValidCpf());
        body.put("birthDate", "1990-01-01");
        body.put("mobilePhone", "11999999999");
        body.put("incomeValue", 5000);
        body.put("address", "Avenida Paulista");
        body.put("addressNumber", "1000");
        body.put("province", "Bela Vista");
        body.put("postalCode", "01310100");
        return body;
    }

    /** Um CPF ficticio com os digitos verificadores certos (o sandbox valida o formato, nao a pessoa). */
    static String randomValidCpf() {
        int[] d = new int[11];
        for (int i = 0; i < 9; i++) {
            d[i] = RANDOM.nextInt(10);
        }
        d[9] = checkDigit(d, 9, 10);
        d[10] = checkDigit(d, 10, 11);
        StringBuilder sb = new StringBuilder();
        for (int v : d) {
            sb.append(v);
        }
        return sb.toString();
    }

    private static int checkDigit(int[] d, int length, int weightStart) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += d[i] * (weightStart - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }
}
