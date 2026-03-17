package com.ospaulos.vupth.web.rest.address.controller;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.api.address.dto.response.UserAddressResponse;
import com.ospaulos.vupth.api.address.usecase.*;
import com.ospaulos.vupth.api.user.dto.response.useraddress.UserAddressAndGeneralInfosResponse;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/users/me/addresses")
public class UserAddressController {

    private final RegisterAddressUseCase registerAddressUseCase;
    private final UpdateAddressUseCase updateAddressUseCase;
    private final DeleteAddressUseCase deleteAddressUseCase;
    private final SetMainAddressUseCase setMainAddressUseCase;
    private final GetAllAddressUseCase getAllAddressUseCase;
    private final GetMainUserAddressUseCase getMainUserAddressUseCase;

    @PostMapping
    public ResponseEntity<UserAddressAndGeneralInfosResponse> createAddress(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid AddressRequest request){

        log.info("CONTROLLER CREATE USER-ADDRESS");

        UserAddressAndGeneralInfosResponse response = registerAddressUseCase.execute(request, user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{userAddressId}")
    public ResponseEntity<UserAddressAndGeneralInfosResponse> updateAddress(
            @AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID userAddressId,
            @RequestBody @Valid UpdateAddressRequest request){
        log.info("CONTROLLER UPDATE USER-ADDRESS");

        UserAddressAndGeneralInfosResponse response = updateAddressUseCase.execute(request, user.getUserId());
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @GetMapping
    public ResponseEntity<List<UserAddressResponse>> getAllUserAddress(
            @AuthenticationPrincipal UserDetailsImpl user
    ){
        log.info("CONTROLLER GET ALL USER-ADDRESS");

        List<UserAddressResponse> addressResponseList = getAllAddressUseCase.execute(user.getUserId());
        return ResponseEntity.status(HttpStatus.OK).body(addressResponseList);
    }

    @PatchMapping("/{userAddressId}/main")
    public String setMainAddress(
            @AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID userAddressId){

        log.info("CONTROLLER SET MAIN USER-ADDRESS");

         return setMainAddressUseCase.execute(user.getUserId(), userAddressId);
    }

    @GetMapping("/main")
    public UserAddressResponse getMainUserAddress(
            @AuthenticationPrincipal UserDetailsImpl user
    ){
        log.info("CONTROLLER GET MAIN USER-ADDRESS");

        return getMainUserAddressUseCase.execute(user.getUserId());
    }

    @DeleteMapping("/{userAddressId}")
    public void deleteUserAddress(
            @AuthenticationPrincipal UserDetailsImpl user,
            @PathVariable UUID userAddressId){

        log.info("CONTROLLER DELETE USER ADDRESS");
        deleteAddressUseCase.execute(user.getUserId(), userAddressId);
    }
}
