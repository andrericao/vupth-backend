package com.ospaulos.vupth.infrastructure.asaas;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Recebe os avisos do Asaas (testes-asaas-sandbox, fatia 1): confere o token, grava um registro por evento e ignora o
 * reenvio (o Asaas repete o aviso quando nao recebe 200). Guarda so valores e estados; nada de dado do cliente.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AsaasWebhookService {

    public enum Result { OK, DUPLICATE, FORBIDDEN, DISABLED, INVALID }

    private final AsaasProperties properties;
    private final AsaasEventRepository events;

    /** O pagamento do atendimento (DEC-119): sem ele (testes de unidade) o aviso so e registrado. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.domain.payment.service.PaymentService payments;

    public Result receive(String token, JsonNode body) {
        if (!properties.webhookEnabled()) {
            return Result.DISABLED;
        }
        if (token == null || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), properties.webhookToken().getBytes(StandardCharsets.UTF_8))) {
            return Result.FORBIDDEN;
        }
        String event = text(body, "event");
        JsonNode payment = body == null ? null : body.get("payment");
        if (event == null) {
            return Result.INVALID;
        }
        String paymentId = payment == null ? null : text(payment, "id");
        String eventId = text(body, "id");
        if (eventId == null) {
            eventId = event + ":" + paymentId + ":" + text(body, "dateCreated");
        }
        if (events.existsByEventId(eventId)) {
            return Result.DUPLICATE;
        }
        AsaasEvent saved = new AsaasEvent();
        saved.setId(UUID.randomUUID());
        saved.setEventId(truncate(eventId, 80));
        saved.setEvent(truncate(event, 60));
        saved.setPaymentId(truncate(paymentId, 60));
        saved.setExternalReference(payment == null ? null : truncate(text(payment, "externalReference"), 80));
        saved.setValue(payment == null ? null : decimal(payment, "value"));
        saved.setNetValue(payment == null ? null : decimal(payment, "netValue"));
        saved.setStatus(payment == null ? null : truncate(text(payment, "status"), 40));
        saved.setBillingType(payment == null ? null : truncate(text(payment, "billingType"), 30));
        saved.setReceivedAt(OffsetDateTime.now());
        events.save(saved);
        log.info("[Asaas] {} payment={} value={} net={}", event, paymentId, saved.getValue(), saved.getNetValue());
        confirmAttendance(event, paymentId, saved);
        return Result.OK;
    }

    /** O Pix recebido ou confirmado quita o atendimento da cobranca (externalReference = id do atendimento). */
    private void confirmAttendance(String event, String paymentId, AsaasEvent saved) {
        if (payments == null || paymentId == null || saved.getExternalReference() == null) {
            return;
        }
        try {
            boolean paid = "PAYMENT_RECEIVED".equals(event) || "PAYMENT_CONFIRMED".equals(event);
            payments.onGatewayEvent(UUID.fromString(saved.getExternalReference()), paymentId, saved.getStatus(), paid);
        } catch (IllegalArgumentException notAnAttendance) {
            // cobranca de teste do ADMIN sem atendimento: so o registro
        } catch (RuntimeException e) {
            log.error("[Asaas] nao deu para atualizar o atendimento da cobranca {}: {}", paymentId, e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isNumber() ? null : v.decimalValue().setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
