package com.ospaulos.vupth.web.rest.report;

import com.ospaulos.vupth.api.report.dto.response.OwnerReportResponse;
import com.ospaulos.vupth.domain.report.service.ReportService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /owner/me/reports?dateFrom=&dateTo=&groupBy=day|week|month&establishmentId=…} — o relatorio do dono com
 * as lojas dele juntas, cada uma e a serie por periodo (multi-loja-do-dono). Datas ISO inclusivas, no dia da loja;
 * {@code establishmentId} repetivel escolhe as lojas (sem ele, todas). So OWNER.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/owner/me")
public class OwnerReportController {

    private final ReportService reportService;

    @GetMapping("/reports")
    public ResponseEntity<OwnerReportResponse> reports(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(defaultValue = "day") String groupBy,
            @RequestParam(name = "establishmentId", required = false) List<UUID> establishmentIds,
            @AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(reportService.getOwnerReport(user.getUserId(), dateFrom, dateTo, groupBy, establishmentIds));
    }
}
