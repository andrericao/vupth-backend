package com.ospaulos.vupth.api.user.dto.response.auth;

import java.time.OffsetDateTime;

/**
 * {@code GET /auth/account-deletion/{token}}: de qual conta e o link, com o e-mail mascarado
 * ("an***@gm***.com"), para a tela confirmar antes de pedir a senha.
 */
public record AccountDeletionLinkResponse(
        String email,
        OffsetDateTime expiresAt
) {
}
