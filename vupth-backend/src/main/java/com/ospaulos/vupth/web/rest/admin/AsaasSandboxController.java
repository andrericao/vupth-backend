package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.ChargeRequest;
import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.ChargeResponse;
import com.ospaulos.vupth.api.admin.dto.AsaasSandboxDtos.Report;
import com.ospaulos.vupth.domain.admin.service.AsaasSandboxReportService;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Os testes de pagamento no sandbox do Asaas (testes-asaas-sandbox, fatia 1). So ADMIN com a permissao FINANCE:
 * {@code POST .../charges} cria uma cobranca no sandbox e {@code GET .../report} mostra para onde vai cada centavo.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/finance/asaas/sandbox")
public class AsaasSandboxController {

    private static final Set<String> BILLING = Set.of("PIX", "BOLETO", "CREDIT_CARD", "UNDEFINED");

    private final AsaasClient client;
    private final AsaasSandboxReportService reports;

    @PostMapping("/charges")
    public ResponseEntity<ChargeResponse> createCharge(@Valid @RequestBody ChargeRequest request) {
        String billing = request.billingType() == null || request.billingType().isBlank()
                ? "PIX" : request.billingType().trim().toUpperCase(java.util.Locale.ROOT);
        if (!BILLING.contains(billing)) {
            throw new com.ospaulos.vupth.infrastructure.asaas.AsaasException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "ASAAS_BILLING_TYPE_INVALID", "billing_type deve ser PIX, BOLETO, CREDIT_CARD ou UNDEFINED.");
        }
        client.requireSandbox(); // a rota de teste nunca cobra no real (DEC-119: o real so pelo fluxo normal)
        String customer = client.ensureCustomer(request.cpfCnpj());
        String reference = request.attendanceId() == null ? null : request.attendanceId().toString();
        AsaasClient.Charge charge = client.createCharge(customer, request.amount(), billing, reference,
                "VUPTH sandbox - teste de pagamento");
        return ResponseEntity.status(HttpStatus.CREATED).body(new ChargeResponse(charge.id(), charge.status(), charge.value(),
                charge.billingType(), charge.invoiceUrl(), charge.externalReference(),
                "No painel do sandbox do Asaas, abra a cobranca e simule o pagamento; o aviso chega em /webhooks/asaas "
                        + "e o relatorio (GET /admin/finance/asaas/sandbox/report) mostra para onde foi cada centavo."));
    }

    @GetMapping("/report")
    public ResponseEntity<Report> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(reports.report(from, to));
    }
}
