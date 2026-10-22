package com.ospaulos.vupth.web.rest.support.controller;

import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminSupportMessageRow;
import com.ospaulos.vupth.domain.support.service.SupportContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /admin/support/messages} — a caixa de suporte do painel (admin-caixa-de-suporte): as mensagens do
 * formulario, da mais nova para a mais antiga, paginadas ({@code page} de 0, {@code size} padrao 20, ate 100).
 * So ADMIN ({@code /admin/**}), com a permissao SUPPORT.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/support")
public class AdminSupportMessageController {

    private final SupportContactService supportContactService;

    @GetMapping("/messages")
    public ResponseEntity<AdminPage<AdminSupportMessageRow>> messages(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(supportContactService.messages(page, size));
    }
}
