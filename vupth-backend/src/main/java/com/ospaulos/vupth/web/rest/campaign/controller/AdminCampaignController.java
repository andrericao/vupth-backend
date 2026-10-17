package com.ospaulos.vupth.web.rest.campaign.controller;

import com.ospaulos.vupth.api.campaign.dto.AdminCampaignRequest;
import com.ospaulos.vupth.api.campaign.dto.AdminCampaignResponse;
import com.ospaulos.vupth.domain.campaign.service.AdminCampaignService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Campanhas pelo painel (campanha-pelo-painel, DEC-69). So o papel ADMIN ({@code /admin/**}, 403 para os
 * outros); o ADMIN so nasce por migracao, nunca pelo app.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/campaigns")
public class AdminCampaignController {

    private final AdminCampaignService adminCampaignService;

    @GetMapping
    public ResponseEntity<List<AdminCampaignResponse>> list() {
        return ResponseEntity.ok(adminCampaignService.list());
    }

    @PostMapping
    public ResponseEntity<AdminCampaignResponse> create(@RequestBody @Valid AdminCampaignRequest request,
                                                        @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminCampaignService.create(principal.getUserId(), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AdminCampaignResponse> update(@PathVariable UUID id, @RequestBody @Valid AdminCampaignRequest request,
                                                        @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminCampaignService.update(principal.getUserId(), id, request));
    }

    @PatchMapping("/{id}/pause")
    public ResponseEntity<AdminCampaignResponse> pause(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminCampaignService.pause(principal.getUserId(), id));
    }

    @PatchMapping("/{id}/resume")
    public ResponseEntity<AdminCampaignResponse> resume(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminCampaignService.resume(principal.getUserId(), id));
    }

    @PatchMapping("/{id}/end")
    public ResponseEntity<AdminCampaignResponse> end(@PathVariable UUID id, @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok(adminCampaignService.end(principal.getUserId(), id));
    }
}
