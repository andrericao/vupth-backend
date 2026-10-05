package com.ospaulos.vupth.unit.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.mail.MailInfoContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — {@link MailInfoContributor}, o bloco {@code mail} do {@code /actuator/info}.
 *
 * <p>Este bloco existe para responder por curl a pergunta que travou o item #1 por quatro dias:
 * "o deploy pegou a env var nova?". Os dois riscos que os testes guardam sao opostos: o campo
 * precisa dizer a verdade sobre o bean que subiu (senao troca uma testemunha ruim por outra) e
 * <b>nao pode vazar chave nem senha</b> — o {@code /actuator/info} do staging e publico.
 */
class MailInfoContributorTest {

    /** Dubles com nome de classe distinto: o campo {@code sender} tem de vir DELES, nao do provider. */
    static class FakeBrevoSender implements EmailSender {
        @Override public void sendPasswordResetCode(String toEmail, String code) { }
        @Override public void send(String toEmail, String subject, String body) { }
    }

    static class FakeSmtpSender implements EmailSender {
        @Override public void sendPasswordResetCode(String toEmail, String code) { }
        @Override public void send(String toEmail, String subject, String body) { }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mailOf(MailInfoContributor contributor) {
        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        return (Map<String, Object>) builder.build().getDetails().get("mail");
    }

    private MailInfoContributor contributor(EmailSender sender, String provider, String apiKey,
                                            String smtpHost, String smtpPassword) {
        return new MailInfoContributor(sender, provider, "no-reply@vupth.app", apiKey, smtpHost, smtpPassword);
    }

    @Nested
    @DisplayName("sender — o nome da classe que subiu e a prova; o provider e so a intencao")
    class Sender {

        @Test
        @DisplayName("publica a classe do bean ativo, nao o texto da env var")
        void publicaAClasseDoBeanAtivo() {
            Map<String, Object> mail = mailOf(
                    contributor(new FakeBrevoSender(), "brevo", "chave", "", ""));

            assertThat(mail).containsEntry("provider", "brevo");
            assertThat(mail).containsEntry("sender", "FakeBrevoSender");
        }

        @Test
        @DisplayName("env var e bean divergentes aparecem como divergentes — o caso que o log de boot nao mostra dias depois")
        void divergenciaEntreEnvVarEBeanFicaVisivel() {
            Map<String, Object> mail = mailOf(
                    contributor(new FakeSmtpSender(), "brevo", "chave", "", ""));

            assertThat(mail.get("provider")).isEqualTo("brevo");
            assertThat(mail.get("sender")).isEqualTo("FakeSmtpSender");
        }
    }

    @Nested
    @DisplayName("credentials — o modo de falha do #1 e credencial ausente com o bean no ar")
    class Credentials {

        @Test
        @DisplayName("brevo com API key preenchida = present")
        void brevoComChave_present() {
            assertThat(mailOf(contributor(new FakeBrevoSender(), "brevo", "xkeysib-abc", "", "")))
                    .containsEntry("credentials", "present");
        }

        @Test
        @DisplayName("brevo sem API key = MISSING — o bean sobe, o endpoint responde 200 e nada sai")
        void brevoSemChave_missing() {
            assertThat(mailOf(contributor(new FakeBrevoSender(), "brevo", "", "", "")))
                    .containsEntry("credentials", "MISSING");
        }

        @Test
        @DisplayName("brevo com chave em BRANCO nao passa por preenchida")
        void brevoComChaveEmBranco_missing() {
            assertThat(mailOf(contributor(new FakeBrevoSender(), "brevo", "   ", "", "")))
                    .containsEntry("credentials", "MISSING");
        }

        @Test
        @DisplayName("smtp exige host E senha")
        void smtpExigeHostESenha() {
            assertThat(mailOf(contributor(new FakeSmtpSender(), "smtp", "", "smtp-relay.brevo.com", "senha")))
                    .containsEntry("credentials", "present");
            assertThat(mailOf(contributor(new FakeSmtpSender(), "smtp", "", "", "senha")))
                    .containsEntry("credentials", "MISSING");
            assertThat(mailOf(contributor(new FakeSmtpSender(), "smtp", "", "smtp-relay.brevo.com", "")))
                    .containsEntry("credentials", "MISSING");
        }

        @Test
        @DisplayName("a chave do OUTRO transporte nao conta — sao credenciais diferentes no painel do Brevo")
        void chaveDoOutroTransporteNaoConta() {
            // provider=brevo com a SMTP key configurada e a confusao que devolve 401 "Key not found".
            assertThat(mailOf(contributor(new FakeBrevoSender(), "brevo", "", "smtp-relay.brevo.com", "senha")))
                    .containsEntry("credentials", "MISSING");
        }

        @Test
        @DisplayName("provider=none nao tem credencial para faltar: n/a, nao MISSING")
        void none_ehNaoAplicavel() {
            assertThat(mailOf(contributor(new FakeSmtpSender(), "none", "", "", "")))
                    .containsEntry("credentials", "n/a");
        }

        @Test
        @DisplayName("o valor da env var e comparado sem diferenciar maiuscula — igual ao @ConditionalOnProperty")
        void comparacaoIgnoraCaixa() {
            assertThat(mailOf(contributor(new FakeBrevoSender(), "BREVO", "", "", "")))
                    .containsEntry("credentials", "MISSING");
        }
    }

    @Nested
    @DisplayName("o /actuator/info do staging e PUBLICO — nenhum segredo pode sair daqui")
    class SemSegredo {

        @Test
        @DisplayName("nem a API key nem a senha de SMTP aparecem no JSON, em nenhuma forma")
        void naoVazaCredencial() throws Exception {
            MailInfoContributor comTudoPreenchido = new MailInfoContributor(
                    new FakeBrevoSender(), "brevo", "no-reply@vupth.app",
                    "xkeysib-segredo-da-api", "smtp-relay.brevo.com", "senha-do-smtp");

            String json = new ObjectMapper().writeValueAsString(mailOf(comTudoPreenchido));

            assertThat(json).doesNotContain("xkeysib-segredo-da-api");
            assertThat(json).doesNotContain("senha-do-smtp");
        }

        @Test
        @DisplayName("o remetente sai inteiro — nao e segredo, e e o que explica e-mail caindo em spam")
        void remetenteSaiInteiro() {
            assertThat(mailOf(contributor(new FakeBrevoSender(), "brevo", "chave", "", "")))
                    .containsEntry("from", "no-reply@vupth.app");
        }
    }
}
