package com.ospaulos.vupth.web.rest.user.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LogoutRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.RefreshTokenRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.usecase.auth.LoginUsecase;
import com.ospaulos.vupth.api.user.usecase.auth.LogoutUseCase;
import com.ospaulos.vupth.api.user.usecase.auth.RefreshTokenUseCase;
import com.ospaulos.vupth.api.user.usecase.profile.RegisterUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("auth")
public class AuthController {

    private final LoginUsecase loginUseCase;
    private final LogoutUseCase logoutUseCase;
    private final RegisterUseCase registerUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;

    @PostMapping("/register")
    public ResponseEntity<UserRegisterResponse> register(@RequestBody @Valid UserRegisterRequest request) throws JsonProcessingException {

        log.info("CONTROLLER AUTH - REGISTER() BEGIN");

        UserRegisterResponse response = registerUseCase.executeRegister(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<UserProfileGeneralInfosAndToken> login(@RequestBody @Valid LoginRequest request){

        log.info("CONTROLLER AUTH 1 - LOGIN() BEGIN");

        UserProfileGeneralInfosAndToken response = loginUseCase.executeLogin(request);

        //log.info("Test: " + response.tokens().accessToken());

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<RefreshTokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenResponse response = refreshTokenUseCase.execute(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request){
        logoutUseCase.execute(request);
        return ResponseEntity.noContent().build();
    }
}
