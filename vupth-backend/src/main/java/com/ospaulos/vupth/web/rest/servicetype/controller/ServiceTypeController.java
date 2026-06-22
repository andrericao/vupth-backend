package com.ospaulos.vupth.web.rest.servicetype.controller;

import com.ospaulos.vupth.api.serviceItem.dto.request.AddServiceTypeKeywordRequest;
import com.ospaulos.vupth.api.serviceItem.dto.response.ServiceTypeSearchResponse;
import com.ospaulos.vupth.domain.serviceItem.service.ServiceTypeKeywordService;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Catalogo de tipos de servico e seus sinonimos/palavras-chave (item 34).
 *
 *   GET  /service-types?q=luzes      — resolve o termo (sinonimo regional) para os
 *                                       tipos canonicos; sem q lista todos (para o "i").
 *                                       Publico (cliente e parceiro consultam).
 *   POST /service-types/{type}/keywords — profissional adiciona um sinonimo ao tipo.
 *                                       409 se ja existir (base do enum ou adicionado).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("service-types")
public class ServiceTypeController {

    private final ServiceTypeKeywordService service;

    @GetMapping
    public ResponseEntity<List<ServiceTypeSearchResponse>> search(
            @RequestParam(required = false) String q) {
        log.info("GET /service-types — q={}", q);
        return ResponseEntity.ok(service.search(q));
    }

    @PostMapping("/{type}/keywords")
    public ResponseEntity<ServiceTypeSearchResponse> addKeyword(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable ServiceItemType type,
            @RequestBody @Valid AddServiceTypeKeywordRequest request) {
        log.info("POST /service-types/{}/keywords — by={} keyword={}",
                type, principal.getUserId(), request.keyword());
        ServiceTypeSearchResponse response =
                service.addKeyword(type, request.keyword(), principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
