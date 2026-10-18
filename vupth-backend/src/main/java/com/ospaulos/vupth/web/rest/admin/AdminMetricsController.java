package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Alerts;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Finance;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Overview;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Series;
import com.ospaulos.vupth.domain.admin.service.AdminMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * Os numeros do painel (painel-de-monitoramento, fatia 2): so ADMIN, cada rota com a sua permissao (OVERVIEW, FINANCE,
 * ALERTS). {@code from} e {@code to} sao ISO com fuso; sem eles, de hoje (dia da loja) ate agora.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminMetricsController {

    private final AdminMetricsService service;

    @GetMapping("/overview")
    public ResponseEntity<Overview> overview(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "same_weekday_last_week") String compare) {
        return ResponseEntity.ok(service.overview(from, to, compare));
    }

    @GetMapping("/series")
    public ResponseEntity<Series> series(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "cuts") String metric,
            @RequestParam(defaultValue = "day") String granularity) {
        return ResponseEntity.ok(service.series(from, to, metric, granularity));
    }

    @GetMapping("/finance")
    public ResponseEntity<Finance> finance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(service.finance(from, to));
    }

    @GetMapping("/alerts")
    public ResponseEntity<Alerts> alerts(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(service.alerts(hours));
    }
}
