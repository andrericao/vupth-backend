package com.ospaulos.vupth.web.rest.user.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ospaulos.vupth.api.user.dto.request.auth.AccountDeletionConfirmRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.AccountDeletionRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.ForgotPasswordRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LogoutRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.RefreshTokenRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.ResetPasswordRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.AccountDeletionLinkResponse;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.usecase.auth.AccountDeletionLinkUseCase;
import com.ospaulos.vupth.api.user.usecase.auth.ForgotPasswordUseCase;
import com.ospaulos.vupth.api.user.usecase.auth.LoginUsecase;
import com.ospaulos.vupth.api.user.usecase.auth.LogoutUseCase;
import com.ospaulos.vupth.api.user.usecase.auth.RefreshTokenUseCase;
import com.ospaulos.vupth.api.user.usecase.auth.ResetPasswordUseCase;
import com.ospaulos.vupth.api.user.usecase.profile.RegisterUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final ForgotPasswordUseCase forgotPasswordUseCase;
    private final ResetPasswordUseCase resetPasswordUseCase;
    private final AccountDeletionLinkUseCase accountDeletionLinkUseCase;

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

    /**
     * Esqueci minha senha (backlog #1) — dispara um código de 6 dígitos por e-mail.
     * Sempre 200, exista o e-mail ou não (anti-enumeração).
     */
    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request){
        forgotPasswordUseCase.execute(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Redefine a senha com o código recebido por e-mail (backlog #1).
     * 400 RESET_CODE_INVALID / RESET_CODE_EXPIRED se o código não servir.
     */
    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request){
        resetPasswordUseCase.execute(request);
        return ResponseEntity.ok().build();
    }

    /**
     * Exclusao pela web (exclusao-por-link): manda o link de uso unico ao e-mail cadastrado.
     * Sempre 202, exista a conta ou nao.
     */
    @PostMapping("/account-deletion/request")
    public ResponseEntity<Void> requestAccountDeletion(@Valid @RequestBody AccountDeletionRequest request){
        accountDeletionLinkUseCase.request(request);
        return ResponseEntity.accepted().build();
    }

    /** De qual conta e o link (e-mail mascarado). 410 ACCOUNT_DELETION_LINK_INVALID se nao servir mais. */
    @GetMapping("/account-deletion/{token}")
    public ResponseEntity<AccountDeletionLinkResponse> peekAccountDeletion(@PathVariable String token){
        return ResponseEntity.ok(accountDeletionLinkUseCase.peek(token));
    }

    /** Confere a senha e exclui, com os portoes do app. 401 senha errada, 409 compromisso vivo, 410 link. */
    @PostMapping("/account-deletion/confirm")
    public ResponseEntity<Void> confirmAccountDeletion(@Valid @RequestBody AccountDeletionConfirmRequest request){
        accountDeletionLinkUseCase.confirm(request);
        return ResponseEntity.noContent().build();
    }
}
