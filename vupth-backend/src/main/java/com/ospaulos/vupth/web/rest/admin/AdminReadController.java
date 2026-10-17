package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AdminAuditRow;
import com.ospaulos.vupth.api.admin.dto.AdminEstablishmentRow;
import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminUserRow;
import com.ospaulos.vupth.domain.admin.service.AdminReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * As listas de leitura do painel (painel-de-monitoramento, fatia 2): so ADMIN ({@code /admin/**}), cada uma com a
 * sua permissao (USERS, ESTABLISHMENTS, AUDIT). Paginadas: {@code page} (de 0) e {@code size} (padrao 20, ate 100).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminReadController {

    private final AdminReadService service;

    @GetMapping("/users")
    public ResponseEntity<AdminPage<AdminUserRow>> users(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.users(q, page, size));
    }

    @GetMapping("/establishments")
    public ResponseEntity<AdminPage<AdminEstablishmentRow>> establishments(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.establishments(q, page, size));
    }

    @GetMapping("/audit")
    public ResponseEntity<AdminPage<AdminAuditRow>> audit(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.audit(from, to, page, size));
    }
}
