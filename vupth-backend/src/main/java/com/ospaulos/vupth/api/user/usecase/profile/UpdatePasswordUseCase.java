package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.dto.request.profile.PasswordRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.exception.InvalidCurrentPasswordException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.service.refreshToken.UtilsTokens;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UpdatePasswordUseCase {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final UtilsTokens utilsTokens;
    private final UserMapper mapper;

    @Transactional
    public UserProfileGeneralInfosAndToken executeUpdatePassword(UUID id, PasswordRequest request){

        BaseUser user = repository.findById(id)
                .orElseThrow(
                        () -> new UserNotFoundException(id)
                );

        // Client #13 — trocar a senha exige provar a senha ATUAL, nao basta o token de acesso.
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.password()));
        repository.save(user);

        RefreshTokenResponse tokensResponse = utilsTokens.rotateTokens(user);

        return new UserProfileGeneralInfosAndToken(
                mapper.toGeneralInfosUserResponse(user),
                tokensResponse);
    }
}
