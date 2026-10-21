package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AdminCutPenaltyDtos.Report;
import com.ospaulos.vupth.domain.admin.service.AdminCutPenaltyReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * GET /admin/finance/cuts-and-penalties — o que o app ganha no corte e na multa, por atendimento, dia ou mes
 * (relatorio-corte-e-multa-por-atendimento, DEC-93). So ADMIN com a permissao FINANCE. E o unico relatorio com a taxa
 * do Asaas e o lucro do app (DEC-94: nenhum outro perfil os ve).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/finance")
public class AdminCutPenaltyReportController {

    private final AdminCutPenaltyReportService service;

    @GetMapping("/cuts-and-penalties")
    public ResponseEntity<Report> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(name = "group_by", defaultValue = "attendance") String groupBy) {
        return ResponseEntity.ok(service.report(from, to, groupBy));
    }
}
