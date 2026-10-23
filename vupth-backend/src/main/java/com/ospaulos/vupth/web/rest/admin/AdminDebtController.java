package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.debt.service.ProfessionalDebtService;
import com.ospaulos.vupth.domain.debt.service.ProfessionalDebtService.Row;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * divida-por-fora-e-abatimento (DEC-115): o Painel liga a divida real (so depois de o Asaas estar implantado; ate la tudo e
 * teste), ve quem deve e quita depois do Pix ao app.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminDebtController {

    private final ProfessionalDebtService debts;
    private final AppSettingService settings;

    public record Flag(@NotNull @JsonProperty("real") Boolean real) {}

    public record Settled(@JsonProperty("settled_amount") BigDecimal settledAmount) {}

    @GetMapping("/settings/debt")
    public ResponseEntity<Flag> flag() {
        return ResponseEntity.ok(new Flag(debts.isReal()));
    }

    @PutMapping("/settings/debt")
    public ResponseEntity<Flag> setFlag(@AuthenticationPrincipal UserDetailsImpl user, @RequestBody @jakarta.validation.Valid Flag body) {
        settings.setFlag(AppSettingService.DEBT_REAL, body.real(), user.getUserId());
        return ResponseEntity.ok(new Flag(debts.isReal()));
    }

    @GetMapping("/debts")
    public ResponseEntity<List<Row>> open() {
        return ResponseEntity.ok(debts.openRows());
    }

    @PostMapping("/debts/{professionalId}/settle")
    public ResponseEntity<Settled> settle(@PathVariable UUID professionalId) {
        return ResponseEntity.ok(new Settled(debts.settle(professionalId)));
    }
}
