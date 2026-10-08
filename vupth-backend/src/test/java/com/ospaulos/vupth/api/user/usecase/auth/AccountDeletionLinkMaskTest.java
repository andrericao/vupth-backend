package com.ospaulos.vupth.api.user.usecase.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O e-mail mascarado do link de exclusao e o hash do token (exclusao-por-link). */
class AccountDeletionLinkMaskTest {

    @Test
    @DisplayName("mascara: 2 letras do nome e do dominio, o resto vira ***; o sufixo fica")
    void mask() {
        assertThat(AccountDeletionLinkUseCase.mask("andre@gmail.com")).isEqualTo("an***@gm***.com");
        assertThat(AccountDeletionLinkUseCase.mask("a@b.com.br")).isEqualTo("a***@b***.com.br");
        assertThat(AccountDeletionLinkUseCase.mask("joao@localhost")).isEqualTo("jo***@lo***");
        assertThat(AccountDeletionLinkUseCase.mask("sem-arroba")).isEqualTo("***");
    }

    @Test
    @DisplayName("sha256: hex de 64 caracteres, deterministico")
    void sha256() {
        String h = AccountDeletionLinkUseCase.sha256("abc");
        assertThat(h).hasSize(64).isEqualTo(AccountDeletionLinkUseCase.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
