package com.ospaulos.vupth.infrastructure.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

/** DEC-2: o codigo de "esqueci minha senha" so sai pelo e-mail — nunca pelo log. */
@ExtendWith(OutputCaptureExtension.class)
class NoOpEmailSenderTest {

    @Test
    @DisplayName("provider=none: o log registra o pedido, mas nao o codigo")
    void resetCodeNeverLogged(CapturedOutput output) {
        new NoOpEmailSender().sendPasswordResetCode("ana@vupth.com", "482913");

        assertThat(output).contains("[MAIL:noop]").contains("ana@vupth.com");
        assertThat(output).doesNotContain("482913");
    }
}
