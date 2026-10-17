package com.ospaulos.vupth.infrastructure.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publica no {@code /actuator/info} qual transporte de e-mail esta REALMENTE no ar.
 *
 * <p><b>Por que existe (2026-08-26).</b> O item #1 ficou quatro dias travado sem ninguem conseguir
 * responder "o deploy pegou o {@code MAIL_PROVIDER} novo?". O staging expoe so
 * {@code health,info,metrics} — <b>nao existe {@code /actuator/env}</b> — entao a unica testemunha
 * do valor efetivo era a linha de boot do {@code @PostConstruct}, que some do painel do Render
 * assim que o log rola. Um campo aqui troca "cacar linha de log" por um curl, e o curl pode rodar
 * de novo a qualquer hora, inclusive dias depois do boot.
 *
 * <p><b>O {@code sender} e a prova, o {@code provider} e so a intencao.</b> O provider e o texto da
 * env var; o sender e o nome da classe que o Spring de fato instanciou. Env var valendo
 * {@code brevo} com {@code SmtpEmailSender} no ar seria condicao quebrada — e exatamente o tipo de
 * divergencia que um campo so nao mostraria. {@link ClassUtils#getUserClass} porque um dia um proxy
 * do Spring transformaria o nome em {@code ...$$SpringCGLIB$$0} e a prova viraria ruido.
 *
 * <p><b>Nenhum segredo sai daqui.</b> Chave e senha viram um estado de tres valores
 * ({@code present} / {@code MISSING} / {@code n/a}), que e o que importa: o modo de falha desta
 * feature e credencial ausente com o bean no ar — o endpoint responde 200 (anti-enumeracao) e o
 * e-mail simplesmente nao chega. O {@code /actuator/info} do staging e publico; o valor da chave
 * nao pode aparecer nem mascarado.
 */
@Component
public class MailInfoContributor implements InfoContributor {

    /** A credencial que ESTE provider precisa esta preenchida. Nao diz que ela e valida. */
    static final String PRESENT = "present";

    /** Faltando: o bean sobe, o endpoint responde 200 e todo envio morre. Em CAIXA ALTA para saltar aos olhos. */
    static final String MISSING = "MISSING";

    /** O provider no ar nao usa credencial ({@code none}) — ausencia aqui nao e defeito. */
    static final String NOT_APPLICABLE = "n/a";

    private final EmailSender emailSender;
    private final String provider;
    private final String brevoApiKey;
    private final String smtpHost;
    private final String smtpPassword;

    public MailInfoContributor(
            EmailSender emailSender,
            @Value("${vupth.mail.provider:none}") String provider,
            @Value("${vupth.mail.brevo.api-key:}") String brevoApiKey,
            @Value("${spring.mail.host:}") String smtpHost,
            @Value("${spring.mail.password:}") String smtpPassword) {
        this.emailSender = emailSender;
        this.provider = provider;
        this.brevoApiKey = brevoApiKey;
        this.smtpHost = smtpHost;
        this.smtpPassword = smtpPassword;
    }

    @Override
    public void contribute(Info.Builder builder) {
        // LinkedHashMap para o JSON sair na ordem em que se le o diagnostico: o que foi pedido,
        // o que subiu, se da para enviar. O remetente (from) ficou de fora: o info e publico (S6, DEC-81).
        Map<String, Object> mail = new LinkedHashMap<>();
        mail.put("provider", provider);
        mail.put("sender", ClassUtils.getUserClass(emailSender).getSimpleName());
        mail.put("credentials", credentialsStatus());
        builder.withDetail("mail", mail);
    }

    /**
     * Qual credencial conta depende do transporte: o Brevo usa a API key v3 (header HTTP) e o SMTP
     * usa host + senha. Sao chaves DIFERENTES no painel do Brevo — trocar uma pela outra devolve
     * {@code 401 Key not found}, que este campo nao pega: aqui so se afirma que ha algo preenchido.
     */
    private String credentialsStatus() {
        if ("brevo".equalsIgnoreCase(provider)) {
            return isBlank(brevoApiKey) ? MISSING : PRESENT;
        }
        if ("smtp".equalsIgnoreCase(provider)) {
            return isBlank(smtpHost) || isBlank(smtpPassword) ? MISSING : PRESENT;
        }
        return NOT_APPLICABLE;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
