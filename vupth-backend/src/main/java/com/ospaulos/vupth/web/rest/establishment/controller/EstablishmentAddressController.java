package com.ospaulos.vupth.web.rest.establishment.controller;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateEstablishmentAddressRequest;
import com.ospaulos.vupth.api.address.dto.response.EstablishmentAddressResponse;
import com.ospaulos.vupth.domain.establishment.service.EstablishmentAddressService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/establishments")
public class EstablishmentAddressController {

    private final EstablishmentAddressService establishmentAddressService;

    @PostMapping("/{establishmentId}/addresses")
    public ResponseEntity<EstablishmentAddressResponse> createAddress(
            @RequestBody @Valid AddressRequest request,
            @PathVariable UUID establishmentId,
            @AuthenticationPrincipal UserDetailsImpl owner){

        log.info("CONTROLLER CREATE ESTABLISHMENT ADDRESS");
        EstablishmentAddressResponse response = establishmentAddressService.registerAddress(request, establishmentId, owner.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Corrige o endereco ja cadastrado (backlog #3). Todo campo do corpo e opcional.
     *
     * <p>O {@code addressId} e o {@code addressId} que o proprio {@code EstablishmentAddressResponse}
     * devolve. A posse da loja e conferida no service — a role sozinha nao diz de quem e a loja.</p>
     */
    @PatchMapping("/{establishmentId}/addresses/{addressId}")
    public ResponseEntity<EstablishmentAddressResponse> updateAddress(
            @RequestBody @Valid UpdateEstablishmentAddressRequest request,
            @PathVariable UUID establishmentId,
            @PathVariable UUID addressId,
            @AuthenticationPrincipal UserDetailsImpl owner){

        log.info("CONTROLLER UPDATE ESTABLISHMENT ADDRESS");
        EstablishmentAddressResponse response =
                establishmentAddressService.updateAddress(request, establishmentId, addressId, owner.getUserId());
        return ResponseEntity.ok(response);
    }
}
