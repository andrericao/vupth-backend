package com.ospaulos.vupth.unit.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.mail.MailHealthContributorAutoConfiguration;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — o {@code MailHealthIndicator} do Actuator fica DESLIGADO.
 *
 * <p>Guard de configuracao, irmao do {@link MailTimeoutPropertiesTest}: o estrago nao aparece em
 * teste de logica nenhum, porque a chave nao muda resposta de endpoint de negocio — muda quem
 * responde pelo <b>deploy</b>.
 *
 * <p>O Actuator registra um indicador de mail <b>sozinho</b> sempre que existe um
 * {@code JavaMailSender} no contexto, e esse indicador <b>abre conexao SMTP</b> a cada checagem.
 * Com a 587 bloqueada no Render, cada checagem espera o timeout inteiro e o {@code /actuator/health}
 * agregado responde <b>DOWN</b> — e o job {@code deploy-prod} do {@code ci.yml} espera exatamente
 * esse endpoint com {@code --max-time 10}. Resultado: a espera nunca se satisfaz e o
 * {@code rollback-prod} reverte um deploy que estava correto. Homolog escapa por esperar o grupo
 * {@code readiness}, que so tem {@code readinessState} + {@code db}.
 *
 * <p>A razao de fundo, que e o que o teste protege: e-mail aqui e <b>best-effort</b> (o
 * {@code ForgotPasswordUseCase} engole a falha de proposito). Uma instancia que serve toda a API
 * nao esta doente porque um terceiro esta fora do ar.
 *
 * <p>⚠️ O terceiro teste existe para o segundo <b>nao passar por engano</b>: o indicador so existe
 * quando ha um {@code JavaMailSender} no contexto, entao um assert de "nao achei o indicador" pode
 * ficar verde por <b>motivo errado</b> — inclusive com a chave apagada. Por isso este teste monta um
 * contexto proprio <b>com</b> um sender e prova os dois lados. E a licao do teste de fuso do BUG2b,
 * que ficava verde no CI em UTC <b>com o bug presente</b>.
 *
 * <p>⚠️ Detalhe de configuracao que sustenta o alcance disto: {@code spring.mail.host=${MAIL_HOST:}}
 * cria o {@code JavaMailSenderImpl} <b>mesmo com host vazio</b> ({@code @ConditionalOnProperty} casa
 * com propriedade presente e nao-{@code false}) — sem a chave, o indicador subiria em <b>todo</b>
 * ambiente, nao so onde o SMTP esta ligado. Medido em 17/08: {@code /actuator/health} de homolog =
 * <b>503 DOWN em 10,7s</b>, {@code /actuator/health/readiness} = UP em 0,54s.
 */
class MailHealthIndicatorDisabledTest {

    private static final String CHAVE = "management.health.mail.enabled";

    private Properties carregaApplicationProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/config/application.properties")) {
            assertThat(in).as("config/application.properties no classpath").isNotNull();
            props.load(in);
        }
        return props;
    }

    /** Contexto minimo com um JavaMailSender presente — o gatilho do auto-config do indicador. */
    private ApplicationContextRunner contextoComMailSender() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailHealthContributorAutoConfiguration.class))
                .withBean(JavaMailSenderImpl.class);
    }

    @Test
    @DisplayName("a chave existe e desliga o indicador por default, ajustavel por env var")
    void chaveDeHealthDeMail_existeComDefaultFalse() throws Exception {
        assertThat(carregaApplicationProperties().getProperty(CHAVE))
                .as("sem esta chave o /actuator/health agregado depende do SMTP, e o deploy-prod espera esse endpoint")
                .isEqualTo("${MAIL_HEALTH_ENABLED:false}");
    }

    @Test
    @DisplayName("com o valor do application.properties, o indicador de mail NAO sobe")
    void comOValorDoArquivo_indicadorNaoSobe() throws Exception {
        String valorDoArquivo = carregaApplicationProperties().getProperty(CHAVE);

        contextoComMailSender()
                .withPropertyValues(CHAVE + "=" + valorDoArquivo) // placeholder resolvido pelo Environment
                .run(ctx -> assertThat(ctx)
                        .as("um indicador de mail no agregado faz o /actuator/health abrir SMTP a cada checagem")
                        .doesNotHaveBean(HealthContributor.class));
    }

    @Test
    @DisplayName("controle: sem a chave o indicador SOBE — e o que este guard impede de voltar")
    void semAChave_indicadorSobe() {
        contextoComMailSender()
                .run(ctx -> assertThat(ctx)
                        .as("se este assert falhar, o teste acima virou verde vazio e nao guarda mais nada")
                        .hasSingleBean(HealthContributor.class));
    }
}
