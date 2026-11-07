package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.admin.service.AdminSessionService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * sessao-configuravel-do-painel (DEC-187): a duracao da sessao, que vale para todos os usuarios do painel. Fica fora de
 * {@code /admin/auth} para passar pela porta do painel: pede o token com o segundo fator e, sem permissao mapeada, so o dono abre.
 * A duracao nova vale para a proxima entrada; a sessao aberta segue com a que tinha.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/session-settings")
public class AdminSessionSettingsController {

    private final AdminSessionService sessions;

    public record SessionSettings(
            @JsonProperty("idle_minutes") int idleMinutes,
            @JsonProperty("options") List<Integer> options,
            @JsonProperty("max_days") int maxDays) {}

    public record SessionSettingsRequest(@JsonProperty("idle_minutes") @NotNull Integer idleMinutes) {}

    @GetMapping
    public ResponseEntity<SessionSettings> get() {
        return ResponseEntity.ok(new SessionSettings(sessions.idleMinutes(), AdminSessionService.IDLE_OPTIONS,
                AdminSessionService.MAX_DAYS));
    }

    @PutMapping
    public ResponseEntity<SessionSettings> change(@AuthenticationPrincipal UserDetailsImpl user,
                                                  @RequestBody @Valid SessionSettingsRequest request) {
        sessions.changeIdleMinutes(request.idleMinutes(), user.getUserId());
        return get();
    }
}
