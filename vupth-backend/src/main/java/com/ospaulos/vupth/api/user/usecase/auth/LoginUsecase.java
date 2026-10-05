package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.domain.user.exception.EmailNotRegisteredException;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.mapper.UserMapper;
import com.ospaulos.vupth.domain.refreshToken.model.RefreshToken;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import com.ospaulos.vupth.infrastructure.security.jwt.token.JwtTokenProvider;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
@Slf4j
@Transactional
@RequiredArgsConstructor
public class LoginUsecase {

    private final UserMapper userMapper;
    private final JwtTokenProvider providerToken;
    private final RefreshTokenProvider refreshTokenProvider;
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;

    public UserProfileGeneralInfosAndToken executeLogin(LoginRequest request){

        log.info("USECASE LOGIN: " + request.email());

        // BAK-6 (D2): e-mail sem conta tem resposta propria (401 EMAIL_NOT_FOUND); senha errada
        // segue 401 BAD_CREDENTIALS. O AuthenticationManager esconde a diferenca de proposito.
        if (!userRepository.existsByEmail(request.email())) {
            throw new EmailNotRegisteredException();
        }

        var authenticationToken =
                new UsernamePasswordAuthenticationToken(
                        request.email(), request.password());

        Authentication authentication = authenticationManager.authenticate(authenticationToken);

        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();

        BaseUser user = userDetails.getUser();

        String accessToken = providerToken.createToken(userDetails);

        RefreshToken refreshToken = refreshTokenProvider.createRefreshToken(user);

        Long refreshExpiresIn = Duration.between(
                OffsetDateTime.now(), refreshToken.getExpiryDate()
        ).getSeconds();

        RefreshTokenResponse refreshTokenResponse =
                new RefreshTokenResponse(accessToken, refreshToken.getToken(), refreshExpiresIn);

        UserProfileGeneralInfosResponse userResponse = userMapper.toGeneralInfosUserResponse(user);

      return new UserProfileGeneralInfosAndToken(userResponse, refreshTokenResponse);
    }
}
