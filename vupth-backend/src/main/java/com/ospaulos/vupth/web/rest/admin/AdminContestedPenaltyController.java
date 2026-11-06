package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.admin.dto.AdminContestedPenaltyRow;
import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.domain.admin.service.AdminContestedPenaltyService;
import com.ospaulos.vupth.domain.client.service.PenaltyContestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * multa-contestavel (F5, permissao FINANCE pelo prefixo {@code /admin/multas}): lista as multas contestadas e o ADMIN decide, com
 * {@code UPHOLD} (a multa volta a valer) ou {@code VOID} (anula).
 */
@RestController
@RequiredArgsConstructor
public class AdminContestedPenaltyController {

    private final AdminContestedPenaltyService listService;
    private final PenaltyContestService contestService;

    public record ResolveRequest(@NotBlank @Pattern(regexp = "UPHOLD|VOID") @JsonProperty("decision") String decision) {}

    public record ResolveResponse(@JsonProperty("id") UUID id,
                                  @JsonProperty("resolution") String resolution,
                                  @JsonProperty("resolved_at") OffsetDateTime resolvedAt) {}

    @GetMapping("/admin/multas-contestadas")
    public ResponseEntity<AdminPage<AdminContestedPenaltyRow>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(listService.list(page, size));
    }

    @PostMapping("/admin/multas-contestadas/{id}/resolve")
    public ResponseEntity<ResolveResponse> resolve(@PathVariable UUID id, @RequestBody @Valid ResolveRequest body) {
        var e = contestService.resolve(id, "UPHOLD".equals(body.decision()));
        return ResponseEntity.ok(new ResolveResponse(e.getId(), e.getResolution(), e.getResolvedAt()));
    }
}
