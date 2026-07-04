package com.ospaulos.vupth.web.rest.notification.controller;

import com.ospaulos.vupth.api.notification.dto.request.DeviceTokenRequest;
import com.ospaulos.vupth.api.notification.dto.response.DeviceTokenResponse;
import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import com.ospaulos.vupth.domain.notification.service.DeviceTokenService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Registro de device tokens para notificacoes push (backlog #1).
 *
 * Qualquer usuario autenticado (CLIENT/PROFESSIONAL/OWNER) pode registrar/remover o token do seu
 * aparelho. O {@code userId} vem sempre do JWT — nunca do body.
 */
@Slf4j
@RestController
@RequestMapping("/devices")
@AllArgsConstructor
public class DeviceController {

    private final DeviceTokenService deviceTokenService;

    /**
     * POST /devices — registra (ou reatribui) o device token do usuario logado. Idempotente por token.
     */
    @PostMapping
    public ResponseEntity<DeviceTokenResponse> register(
            @RequestBody @Valid DeviceTokenRequest request,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("DEVICE CONTROLLER REGISTER — user={} platform={}", principal.getUserId(), request.platform());

        DeviceToken saved = deviceTokenService.register(
                principal.getUserId(), request.token(), request.platform());

        return ResponseEntity.ok(DeviceTokenResponse.fromEntity(saved));
    }

    /**
     * DELETE /devices/{token} — remove o device token (logout). Idempotente (204 mesmo se nao existir).
     */
    @DeleteMapping("/{token}")
    public ResponseEntity<Void> remove(
            @PathVariable String token,
            @AuthenticationPrincipal UserDetailsImpl principal) {

        log.info("DEVICE CONTROLLER REMOVE — user={}", principal.getUserId());

        deviceTokenService.remove(token);
        return ResponseEntity.noContent().build();
    }
}
