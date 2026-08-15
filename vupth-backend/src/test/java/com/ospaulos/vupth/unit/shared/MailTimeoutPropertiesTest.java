package com.ospaulos.vupth.unit.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — os timeouts de SMTP do {@code application.properties}.
 *
 * <p>Guard de configuracao, no mesmo espirito do {@code GitCommitInfoContributorTest}: o estrago
 * nao aparece em nenhum teste de logica, porque a ausencia destas chaves nao muda resultado
 * nenhum — muda quanto tempo o servidor espera antes de desistir, e o default do JavaMail e
 * <b>esperar para sempre</b>.
 *
 * <p>Por que isso importa aqui: o envio roda <b>dentro</b> do {@code @Transactional} do
 * {@code ForgotPasswordUseCase}. Um SMTP que aceita a conexao e nao responde segura a transacao
 * e a conexao do pool ate o socket cair sozinho. No Render free, com pool pequeno, poucas dessas
 * esgotam o pool e derrubam o app inteiro — a partir de um endpoint <b>publico e sem rate
 * limit</b>, que qualquer um pode chamar em sequencia.
 */
class MailTimeoutPropertiesTest {

    /** Casa {@code ${VAR:12345}} e captura o default. */
    private static final Pattern PLACEHOLDER_COM_DEFAULT = Pattern.compile("^\\$\\{[A-Z_]+:(\\d+)}$");

    private static final String[] CHAVES_DE_TIMEOUT = {
            "spring.mail.properties.mail.smtp.connectiontimeout", // aperto de mao TCP
            "spring.mail.properties.mail.smtp.timeout",           // leitura de resposta
            "spring.mail.properties.mail.smtp.writetimeout"       // escrita do corpo
    };

    private Properties carregaApplicationProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
            assertThat(in).as("config/application.properties no classpath").isNotNull();
            props.load(in);
        }
        return props;
    }

    @Test
    @DisplayName("os tres timeouts de SMTP existem e tem default positivo — sem eles o JavaMail espera para sempre")
    void timeoutsDeSmtp_existemComDefaultPositivo() throws Exception {
        Properties props = carregaApplicationProperties();

        for (String chave : CHAVES_DE_TIMEOUT) {
            String valor = props.getProperty(chave);
            assertThat(valor).as("%s ausente — o default do JavaMail e esperar indefinidamente", chave).isNotNull();

            Matcher m = PLACEHOLDER_COM_DEFAULT.matcher(valor.trim());
            assertThat(m.matches())
                    .as("%s = '%s' — esperado ${VAR:millis}, para o valor ser ajustavel por env var sem novo deploy", chave, valor)
                    .isTrue();
            assertThat(Integer.parseInt(m.group(1)))
                    .as("%s: 0 em JavaMail significa SEM timeout, que e exatamente o defeito que este teste guarda", chave)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("o provider de e-mail continua chaveado por env var, com none como default seguro")
    void providerDeEmail_chaveadoPorEnvVarComDefaultNone() throws Exception {
        Properties props = carregaApplicationProperties();

        assertThat(props.getProperty("vupth.mail.provider"))
                .as("trocar o default para smtp faria todo ambiente sem MAIL_HOST tentar entregar em host vazio")
                .isEqualTo("${MAIL_PROVIDER:none}");
    }
}
