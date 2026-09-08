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

    /**
     * Encerra a sessao do aparelho que pediu para sair — e so ela. As sessoes dos outros aparelhos
     * do mesmo usuario continuam de pe (sair do Partner nao desloga o Client).
     *
     * <p>Encerra a <b>sessao</b>, nao o token: se o app mandar um token ja rotacionado, o sucessor
     * dele tambem cai. Sem isso, um logout feito com token velho nao desligava nada.</p>
     */
    @Transactional
    public void execute(LogoutRequest request){
        refreshTokenProvider.revokeSession(request.refreshToken());
    }
}
