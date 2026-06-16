package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * GET /profile/me — fonte canonica do perfil do usuario autenticado.
 *
 * Retorna first_name / last_name / nickname / email / phone / roles da BaseUser,
 * usado pelos dois apps (client e partner) para pre-preencher formularios.
 * Reusa {@link UserProfileGeneralInfosResponse}; em GET o {@code modified_fields}
 * vem como lista vazia.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GetMyProfileUseCase {

    private final UserRepository repository;
    private final UserMapper mapper;

    public UserProfileGeneralInfosResponse execute(UUID userId) {
        log.info("USECASE GET /profile/me — userId={}", userId);

        BaseUser user = repository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        return mapper.toGeneralInfosUserResponse(user);
    }
}
