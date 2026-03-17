package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.dto.request.profile.EmailRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.exception.EmailsAreTheSameException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.service.refreshToken.UtilsTokens;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpdateEmailUseCase {

    private final UserMapper mapper;
    private final UserRepository repository;
    private final UtilsTokens utilsTokens;

    @Transactional
    public UserProfileGeneralInfosAndToken executeUpdateEmail(UUID id, EmailRequest request){

        log.info("USERCASE UPDATE EMAIL - " + request.email());

        BaseUser user = repository.findById(id)
                .orElseThrow(
                        () -> new UserNotFoundException(id)
                );

        if(request.email().equals(user.getEmail())){
            throw new EmailsAreTheSameException();
        }

        user.setEmail(request.email());
        repository.save(user);

        RefreshTokenResponse tokensResponse = utilsTokens.rotateTokens(user);

        log.info("UPDATE E-MAIL USERCASE - " + user.getEmail());

        return new UserProfileGeneralInfosAndToken(
                mapper.toGeneralInfosUserResponse(user),
                tokensResponse);
    }
}
