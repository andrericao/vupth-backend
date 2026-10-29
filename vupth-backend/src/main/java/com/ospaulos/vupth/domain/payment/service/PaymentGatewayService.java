package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * asaas-sandbox-no-fluxo (DEC-119): o Asaas como uma CONEXAO que liga e desliga por configuracao (sandbox ou real, pela URL).
 * Conectado, o pagamento do atendimento sai por uma cobranca Pix do Asaas; desconectado, vale o fluxo de sempre (Pix do
 * profissional, "Ja paguei", divida e multa), que este servico nunca toca. Cobranca aberta num modo termina nele.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentGatewayService {

    /** A cobranca minima do Asaas (sobre o total do cliente). */
    static final BigDecimal MIN_CHARGE = new BigDecimal("5.00");

    private final AsaasProperties properties;
    private final AsaasClient client;
    private final AppSettingService settings;
    private final PaymentAccountService paymentAccounts;

    /** Ligada: a chave esta configurada E a flag {@code asaas.connected} esta ligada. */
    public boolean connected() {
        return properties.keyConfigured() && settings.flag(AppSettingService.ASAAS_CONNECTED, false);
    }

    /** SANDBOX ou REAL (pela URL); so quer dizer algo com a conexao ligada. */
    public String mode() {
        return properties.mode();
    }

    /** Liga ou desliga. Ligar sem chave configurada nao e possivel. */
    public void setConnected(boolean connected, java.util.UUID by) {
        if (connected && !properties.keyConfigured()) {
            throw new AsaasException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "ASAAS_NOT_CONFIGURED",
                    "Nao da para ligar: falta a ASAAS_API_KEY.");
        }
        settings.setFlag(AppSettingService.ASAAS_CONNECTED, connected, by);
    }

    /**
     * Garante a cobranca Pix do atendimento quando a conexao esta ligada e ainda nao ha uma. Qualquer falha ou condicao que nao
     * serve (valor abaixo do minimo do Asaas, sem cliente, real sem CPF) cai no fluxo de sempre: nada e criado e nada quebra.
     */
    /** A tarifa do Pix da conta do Asaas (a que o Painel muda); sem a conta cadastrada, R$ 1,99 (decisao do Andre, 24/10). */
    static final BigDecimal DEFAULT_PIX_FEE = new BigDecimal("1.99");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.domain.finance.repository.FinanceAccountRepository financeAccounts;

    private BigDecimal pixFee() {
        if (financeAccounts == null) {
            return DEFAULT_PIX_FEE;
        }
        return financeAccounts.findByKey(com.ospaulos.vupth.domain.finance.model.FinanceAccount.ASAAS)
                .map(com.ospaulos.vupth.domain.finance.model.FinanceAccount::getPixFee).orElse(DEFAULT_PIX_FEE);
    }

    public void ensureCharge(Attendance attendance, BigDecimal amount) {
        if (attendance.getAsaasPaymentId() != null || !connected() || attendance.getClient() == null
                || amount == null || amount.compareTo(MIN_CHARGE) < 0) {
            return;
        }
        if (!properties.sandbox()) {
            // o real precisa do CPF do cliente, que o app ainda nao guarda: segue o fluxo de sempre ate o Andre decidir (Solicitadas)
            log.info("[Gateway] modo real sem CPF do cliente — fica o fluxo de sempre (attendance={})", attendance.getId());
            return;
        }
        try {
            // o cliente do Asaas e a pessoa logada: o e-mail e o nome do cadastro, sem pedir nada (o Asaas exige o e-mail do titular do cartao)
            AsaasClient.Customer customer = client.ensureCustomer(new AsaasClient.Party(
                    "vupth-user-" + attendance.getClient().getId(), displayName(attendance), attendance.getClient().getEmail()), null);
            List<AsaasClient.Split> splits = splitsFor(attendance, amount);
            AsaasClient.Charge charge = client.createCharge(customer.id(), amount, "PIX", attendance.getId().toString(),
                    "VUPTH - atendimento", splits);
            attendance.setAsaasSplitAmount(splits.stream().map(AsaasClient.Split::fixedValue).reduce(BigDecimal.ZERO, BigDecimal::add));
            AsaasClient.PixQr qr = client.pixQrCode(charge.id());
            attendance.setAsaasPaymentId(charge.id());
            attendance.setAsaasMode(mode());
            attendance.setAsaasStatus(charge.status());
            attendance.setAsaasPixPayload(qr.payload());
            attendance.setAsaasPixImage(qr.encodedImage());
            attendance.setAsaasInvoiceUrl(charge.invoiceUrl());
            log.info("[Gateway] cobranca Pix criada — attendance={} payment={} modo={} valor={}", attendance.getId(), charge.id(),
                    mode(), amount);
        } catch (AsaasException e) {
            log.warn("[Gateway] cobranca nao criada, fica o fluxo de sempre — attendance={}: {}", attendance.getId(), e.getMessage());
        }
    }

    /**
     * pagamento-manual-e-asaas: o atendimento foi quitado a mao ("Recebi"), entao a cobranca do Asaas e cancelada para ninguem pagar
     * duas vezes. Best-effort: sem cobranca, desligado ou recusado pelo Asaas (ja recebida, por exemplo), nada quebra; o webhook
     * continua valendo e nao quita de novo um atendimento ja pago. Devolve se cancelou.
     */
    public boolean cancelCharge(Attendance attendance) {
        String id = attendance.getAsaasPaymentId();
        if (id == null || !properties.keyConfigured()) {
            return false;
        }
        try {
            client.cancelCharge(id);
            attendance.setAsaasStatus("DELETED");
            log.info("[Gateway] cobranca cancelada pelo Recebi manual — attendance={} payment={}", attendance.getId(), id);
            return true;
        } catch (AsaasException e) {
            log.warn("[Gateway] cobranca nao cancelada — attendance={} payment={}: {}", attendance.getId(), id, e.getMessage());
            return false;
        }
    }

    private static String displayName(Attendance attendance) {
        String name = attendance.getClient().getFullName();
        boolean empty = name == null || name.isBlank() || name.contains("null");
        String email = attendance.getClient().getEmail();
        return empty ? (email == null ? "Cliente VUPTH" : email.substring(0, Math.max(1, email.indexOf('@') < 0 ? email.length() : email.indexOf('@')))) : name.trim();
    }

    /**
     * A divisao do pagamento (fatia 1): o que o profissional estipulou vai direto para a carteira da subconta dele; o resto (a taxa,
     * a margem do app e a multa) fica na conta do app. So com conta de recebimento no mesmo modo e SEM cupom (o desconto sai do app,
     * DEC-120, e fica para a fatia 2: o valor do profissional pode passar do cobrado). Sem isso, sem divisao: o fluxo de antes.
     */
    public List<AsaasClient.Split> splitsFor(Attendance attendance, BigDecimal charged) {
        BigDecimal pro = attendance.getProReceives();
        if (pro == null || pro.signum() <= 0) {
            return List.of();
        }
        BigDecimal toPro = pro;
        if (attendance.getDiscount() != null && attendance.getDiscount().signum() > 0) {
            // fatia 2 (DEC-127, decisao do Andre 29/10): com desconto o cobrado nao cobre o que o profissional estipulou; o Asaas
            // entrega o que sobra ja sem a tarifa do Pix, e o app calcula o que ainda deve a ele (pro_receives − split).
            toPro = pro.min(charged.subtract(pixFee())).setScale(2, java.math.RoundingMode.DOWN);
            if (toPro.signum() <= 0) {
                return List.of();
            }
        } else if (pro.compareTo(charged) >= 0) {
            return List.of();
        }
        BigDecimal value = toPro;
        return paymentAccounts.walletFor(attendance.getProfessional().getId(), mode())
                .map(wallet -> List.of(new AsaasClient.Split(wallet, value))).orElse(List.of());
    }
}
