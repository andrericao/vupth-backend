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

    /** virada-do-gateway (DEC-163): o dia em que o Painel ligou o Asaas REAL (texto ISO); vazio = sem aviso. */
    public static final String CONNECTED_AT = "asaas.connected_at";
    /** O aviso aos profissionais dura 7 dias; a regra de recebimento so muda quando ele acaba. */
    public static final int NOTICE_DAYS = 7;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private GatewayNoticeNotifier noticeNotifier;

    /** O aviso de 7 dias: aberto em {@code openedAt}, a regra de recebimento muda em {@code changesAt}. */
    public record Notice(java.time.OffsetDateTime openedAt, java.time.OffsetDateTime changesAt) {}

    private boolean flagOn() {
        return properties.keyConfigured() && settings.flag(AppSettingService.ASAAS_CONNECTED, false);
    }

    private boolean real() {
        return "REAL".equals(properties.mode());
    }

    private java.time.OffsetDateTime openedAt() {
        String at = settings.text(CONNECTED_AT);
        if (at == null || at.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(at);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Quando a regra de recebimento muda (a conexao REAL + 7 dias); {@code null} no sandbox ou sem aviso. */
    private java.time.OffsetDateTime changesAt() {
        java.time.OffsetDateTime opened = real() ? openedAt() : null;
        return opened == null ? null : opened.plusDays(NOTICE_DAYS);
    }

    /** O aviso aberto agora (ligado no REAL e o prazo de 7 dias ainda nao acabou), para o banner dos apps. */
    public java.util.Optional<Notice> notice() {
        java.time.OffsetDateTime changes = changesAt();
        if (!flagOn() || changes == null || !java.time.OffsetDateTime.now().isBefore(changes)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Notice(openedAt(), changes));
    }

    /** A ligacao ja vale para o pagamento: ligada e, no REAL, passados os 7 dias do aviso. O sandbox liga na hora (sem dinheiro). */
    public boolean connected() {
        if (!flagOn()) {
            return false;
        }
        java.time.OffsetDateTime changes = changesAt();
        return changes == null || !java.time.OffsetDateTime.now().isBefore(changes);
    }

    /** Sem CPF o profissional nao abre: so com o Asaas REAL valendo (virada feita). */
    public boolean cpfRequired() {
        return real() && connected();
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
        boolean was = settings.flag(AppSettingService.ASAAS_CONNECTED, false);
        settings.setFlag(AppSettingService.ASAAS_CONNECTED, connected, by);
        if (!connected) {
            settings.setText(CONNECTED_AT, "", by);
        } else if (real() && (!was || openedAt() == null)) {
            // virada-do-gateway: registra o dia da conexao REAL e abre o aviso de 7 dias (so depois de conectar)
            java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
            settings.setText(CONNECTED_AT, now.toString(), by);
            if (noticeNotifier != null) {
                noticeNotifier.notifyProfessionals(now.plusDays(NOTICE_DAYS));
            }
        }
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

    /**
     * estorno-asaas-casos: estorna a cobranca JA PAGA (total, DEC-137) e devolve se o Asaas aceitou. Cobranca ainda aberta nao se
     * estorna, se cancela ({@link #cancelCharge}). Falha do Asaas (saldo, prazo) volta como {@link AsaasException} para o ADMIN ver.
     */
    public void refundCharge(Attendance attendance) {
        String status = client.refund(attendance.getAsaasPaymentId());
        attendance.setAsaasStatus(status == null || status.isBlank() ? "REFUNDED" : status);
        log.info("[Gateway] cobranca estornada — attendance={} payment={}", attendance.getId(), attendance.getAsaasPaymentId());
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
