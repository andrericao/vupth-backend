package com.ospaulos.vupth.api.user.usecase.auth;

import com.ospaulos.vupth.api.user.dto.request.auth.LogoutRequest;
import com.ospaulos.vupth.infrastructure.security.jwt.token.RefreshTokenProvider;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutUseCase {

    private final RefreshTokenProvider refreshTokenProvider;

    @Transactional
    public void execute(LogoutRequest request){
        refreshTokenProvider.revokeToken(request.refreshToken());
    }
}
