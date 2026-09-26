package com.ospaulos.vupth.web.rest.user.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ospaulos.vupth.api.user.dto.request.profile.DeleteAccountRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.EmailRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.PasswordRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserProfileGeneralInfosRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.usecase.profile.*;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("profile")
public class ProfileController {

    private final RegisterUseCase registerUseCase;
    private final DeleteAccountUseCase deleteAccountUseCase;
    private final UpdateEmailUseCase updateEmailUseCase;
    private final UpdatePasswordUseCase updatePasswordUseCase;
    private final UpdateProfileGeneralInfosUseCase updateProfileGeneralInfosUseCase;
    private final GetMyProfileUseCase getMyProfileUseCase;
    private final com.ospaulos.vupth.domain.user.service.ProfilePhotoService profilePhotoService;

    /**
     * GET /profile/me
     * Fonte canonica do perfil do usuario autenticado (first/last/nickname/email/phone/roles).
     * Acesso: qualquer usuario autenticado.
     */
    @GetMapping("/me")
    public ResponseEntity<UserProfileGeneralInfosResponse> getMyProfile(
            @AuthenticationPrincipal UserDetailsImpl user) {

        log.info("CONTROLLER PROFILE GET /me — userId={}", user.getUserId());
        return ResponseEntity.ok(getMyProfileUseCase.execute(user.getUserId()));
    }

    @PostMapping("/register")
    public ResponseEntity<UserRegisterResponse> register(
            @RequestBody @Valid UserRegisterRequest request) throws JsonProcessingException {

        log.info("CONTROLLER AUTH - REGISTER() BEGIN");

        UserRegisterResponse response = registerUseCase.executeRegister(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/general-infos")
    public ResponseEntity<UserProfileGeneralInfosResponse> fillGeneralInfos(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid UserProfileGeneralInfosRequest request){

        log.info("CONTROLLER PROFILE GENERAL INFO START");

        final UserProfileGeneralInfosResponse response = updateProfileGeneralInfosUseCase.executeFillGeneralInfos(
                user.getUserId(),
                request);

        log.info("CONTROLLER PROFILE GENERAL INFO END");

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PatchMapping("/update-email/me")
    public ResponseEntity<UserProfileGeneralInfosAndToken> updateEmail(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid EmailRequest request){

        log.info("CONTROLLER UPDATE EMAIL 1");

        final UserProfileGeneralInfosAndToken response =
                updateEmailUseCase.executeUpdateEmail(user.getUserId(), request);
        return ResponseEntity.status(HttpStatus.OK).body(response);

    }

    @PatchMapping("/update-password/me")
    public ResponseEntity<UserProfileGeneralInfosAndToken> updatePassword(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid PasswordRequest request){

        UserProfileGeneralInfosAndToken responseAndToken =
                updatePasswordUseCase.executeUpdatePassword(
                        user.getUserId(),
                        request);

        return ResponseEntity.status(HttpStatus.OK).body(responseAndToken);

    }

    /**
     * DELETE /profile/delete/me
     * Apaga a conta do usuario autenticado (Partner S19 / LGPD / exigencia das lojas de app).
     *
     * <p>Exige a senha atual no corpo — o token de acesso sozinho nao basta para uma acao
     * irreversivel. O que e pessoal e apagado; os atendimentos ficam, anonimizados, porque
     * pertencem tambem ao profissional e ao estabelecimento.</p>
     *
     * <p>Acesso: qualquer usuario autenticado (os papeis sao acumulativos — um dono tambem
     * precisa poder apagar a propria conta).</p>
     */
    @DeleteMapping("/delete/me")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal UserDetailsImpl user,
            @RequestBody @Valid DeleteAccountRequest request){

        log.info("CONTROLLER PROFILE DELETE /delete/me — userId={}", user.getUserId());

        deleteAccountUseCase.execute(user.getUserId(), request.currentPassword());

        return ResponseEntity.noContent().build();
    }

    /**
     * PUT /profile/me/photo (multipart, campo {@code file}) — foto de perfil (S22; R2, Andre 25/09).
     * JPEG, PNG ou WebP ate 2 MB. Devolve a URL publica, que passa a sair em {@code profile_image_url}.
     */
    @PutMapping(value = "/me/photo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<java.util.Map<String, String>> uploadPhoto(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl user) throws java.io.IOException {
        log.info("CONTROLLER PROFILE PUT /me/photo — userId={} bytes={}", user.getUserId(), file.getSize());
        String url = profilePhotoService.upload(user.getUserId(), file.getBytes());
        return ResponseEntity.ok(java.util.Map.of("profile_image_url", url));
    }

    /** DELETE /profile/me/photo — tira a foto (e apaga o arquivo). */
    @DeleteMapping("/me/photo")
    public ResponseEntity<Void> removePhoto(@AuthenticationPrincipal UserDetailsImpl user) {
        profilePhotoService.remove(user.getUserId());
        return ResponseEntity.noContent().build();
    }
}
