package com.ospaulos.vupth.infrastructure.security.social;

import com.ospaulos.vupth.domain.user.model.SocialProvider;

/**
 * O id-token nao passou na validacao. O motivo fica no log; ao cliente vai
 * apenas INVALID_SOCIAL_TOKEN, porque detalhar qual validacao falhou ajudaria
 * quem esta tentando forjar token.
 */
public class SocialTokenInvalidException extends RuntimeException {
    public SocialTokenInvalidException(SocialProvider provider, String reason) {
        super(String.format("Invalid %s id token: %s", provider, reason));
    }
}
