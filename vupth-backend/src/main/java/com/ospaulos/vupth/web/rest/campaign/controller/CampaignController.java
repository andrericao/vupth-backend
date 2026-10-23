package com.ospaulos.vupth.web.rest.campaign.controller;

import com.ospaulos.vupth.api.campaign.dto.CampaignResponse;
import com.ospaulos.vupth.api.campaign.dto.RedeemCouponRequest;
import com.ospaulos.vupth.domain.campaign.service.CampaignService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Campanhas (DEC-57/61/64). {@code GET /campaigns/active} e publico (a pagina "Campanhas ativas" do suporte
 * e os apps) e, com login, devolve so as campanhas do publico de quem pede; aceitar o cupom e ver os seus pede login.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    @GetMapping("/active")
    public ResponseEntity<List<CampaignResponse>> active(@AuthenticationPrincipal UserDetailsImpl principal) {
        // campanhas-ativas-para-o-client: com login, so as do publico do usuario; sem login, todas as publicas
        return ResponseEntity.ok(principal == null
                ? campaignService.activePublic() : campaignService.activeFor(principal.getUserId()));
    }

    @PostMapping("/redeem")
    public ResponseEntity<CampaignResponse> redeem(@RequestBody @Valid RedeemCouponRequest request,
                                                   @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.redeem(principal.getUserId(), request.code()));
    }

    /** Participar (compartilhe-e-ganhe): adere a uma campanha publica sem cupom. 201; 404, 403, 409 como o {@code redeem}. */
    @PostMapping("/{id}/join")
    public ResponseEntity<CampaignResponse> join(@PathVariable java.util.UUID id,
                                                 @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaignService.join(principal.getUserId(), id));
    }

    @GetMapping("/me")
    public ResponseEntity<List<CampaignResponse>> mine(@AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(campaignService.mine(principal.getUserId()));
    }
}
