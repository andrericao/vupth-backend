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
            AsaasClient.Charge charge = client.createCharge(customer.id(), amount, "PIX", attendance.getId().toString(),
                    "VUPTH - atendimento");
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

    private static String displayName(Attendance attendance) {
        String name = attendance.getClient().getFullName();
        boolean empty = name == null || name.isBlank() || name.contains("null");
        String email = attendance.getClient().getEmail();
        return empty ? (email == null ? "Cliente VUPTH" : email.substring(0, Math.max(1, email.indexOf('@') < 0 ? email.length() : email.indexOf('@')))) : name.trim();
    }
}
