package com.ospaulos.vupth.api.user.usecase.profile;

import com.ospaulos.vupth.api.user.dto.request.profile.UserProfileGeneralInfosRequest;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.user.exception.NotFoundUserException;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class UpdateProfileGeneralInfosUseCase {

    private final UserMapper mapper;
    private final UserRepository repository;
    private final JwtTokenProvider jwtTokenProvider;

    public UserProfileGeneralInfosResponse executeFillGeneralInfos(
            UUID id,
            UserProfileGeneralInfosRequest request) throws NotFoundUserException {

        log.info("USECASE FILL GENERAL INFOS TO id: {}", id);

        BaseUser user = repository.findById(id).orElseThrow(
                () -> new UserNotFoundException(id)
        );

        user.updateIfPresent(request);

        log.info(user.toString());

        var userSaved = repository.save(user);

        log.info("Profile updated successfully!");

        return mapper.toGeneralInfosUserResponse(userSaved);
    }
}
