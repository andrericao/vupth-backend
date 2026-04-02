package com.ospaulos.vupth.web.rest.professional.controller;

import com.ospaulos.vupth.api.professional.dto.request.ProfessionalQuickCreateRequest;
import com.ospaulos.vupth.api.professional.dto.response.ProfessionalQuickCreateResponse;
import com.ospaulos.vupth.api.professional.usecase.QuickCreateProfessionalUseCase;
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
@RequestMapping("professional")
public class ProfessionalQuickCreateController {

    private final QuickCreateProfessionalUseCase useCase;

    /**
     * POST /professional/quick-create
     *
     * Simplified professional registration — MVP with minimum friction for the user.
     * Required fields: name and establishment ID.
     * Opcional: especialidade (pode ser preenchida depois).
     * Um ID fixo (UUID = BaseUser.id) is gerado e nunca muda.
     *
     * Requires: Bearer JWT token (authenticated user).
     */
    @PostMapping("/quick-create")
    public ResponseEntity<ProfessionalQuickCreateResponse> quickCreate(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @RequestBody @Valid ProfessionalQuickCreateRequest request) {

        log.info("POST /professional/quick-create — user={} name={} estab={}",
                principal.getUserId(), request.name(), request.establishmentId());

        ProfessionalQuickCreateResponse response = useCase.execute(principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
