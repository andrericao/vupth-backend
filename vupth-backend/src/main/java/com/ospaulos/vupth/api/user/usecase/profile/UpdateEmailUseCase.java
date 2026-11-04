package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.infrastructure.logging.LogMask;
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

    /** F1: o e-mail antigo e avisado da troca (a porta lateral da conta tomada). Opcional nos testes de unidade. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.infrastructure.mail.AccountSecurityNotifier securityNotifier;

    @Transactional
    public UserProfileGeneralInfosAndToken executeUpdateEmail(UUID id, EmailRequest request){

        log.info("USERCASE UPDATE EMAIL - " + LogMask.email(request.email()));

        BaseUser user = repository.findById(id)
                .orElseThrow(
                        () -> new UserNotFoundException(id)
                );

        if(request.email().equals(user.getEmail())){
            throw new EmailsAreTheSameException();
        }

        String oldEmail = user.getEmail();
        user.setEmail(request.email());
        repository.save(user);

        RefreshTokenResponse tokensResponse = utilsTokens.rotateTokens(user);
        if (securityNotifier != null) {
            securityNotifier.emailChanged(oldEmail, user.getEmail());
        }

        log.info("UPDATE E-MAIL USERCASE - " + LogMask.email(user.getEmail()));

        return new UserProfileGeneralInfosAndToken(
                mapper.toGeneralInfosUserResponse(user),
                tokensResponse);
    }
}
