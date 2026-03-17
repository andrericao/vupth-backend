package com.ospaulos.vupth.web.rest.establishment.controller;

import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentQuickCreateRequest;
import com.ospaulos.vupth.api.establishment.dto.response.EstablishmentQuickCreateResponse;
import com.ospaulos.vupth.api.establishment.usecase.QuickCreateEstablishmentUseCase;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("establishment")
public class EstablishmentQuickCreateController {

    private final QuickCreateEstablishmentUseCase useCase;

    /**
     * POST /establishment/quick-create
     *
     * Simplified registration — MVP with minimum friction for the user.
     * Only the name is required. A fixed UUID is generated on creation and never changes.
     * The owner can complete additional data (services, photo, address) later.
     *
     * Requires: Bearer JWT token (authenticated user).
     */
    @PostMapping("/quick-create")
    public ResponseEntity<EstablishmentQuickCreateResponse> quickCreate(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid EstablishmentQuickCreateRequest request) {

        log.info("POST /establishment/quick-create — user={} name={}", principal.getUserId(), request.name());

        EstablishmentQuickCreateResponse response = useCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
