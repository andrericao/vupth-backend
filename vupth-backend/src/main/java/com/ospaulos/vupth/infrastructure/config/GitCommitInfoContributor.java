package com.ospaulos.vupth.infrastructure.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identidade da instancia em execucao, para o deploy poder ser verificado.
 *
 * <p><b>commit</b> vem da variavel que a hospedagem injeta. O Render define
 * {@code RENDER_GIT_COMMIT} sozinho; o Railway define {@code RAILWAY_GIT_COMMIT_SHA}
 * apenas quando o deploy nasce da integracao com o GitHub — num {@code railway up}
 * disparado pelo CI nao ha contexto de git, e ai o valor fica {@code unknown}.
 *
 * <p><b>startedAt</b> existe por causa disso: e a unica marca que nao depende da
 * hospedagem. Ele diz quando ESTE processo subiu, entao quem verifica um deploy
 * consegue distinguir a instancia nova da anterior mesmo sem commit — basta
 * exigir um inicio posterior ao momento em que o deploy foi disparado.
 *
 * <p>Sem essa distincao, uma verificacao pos-deploy passa contra a instancia
 * ANTIGA: durante o rollout a anterior continua respondendo, e {@code /health}
 * responde UP nas duas.
 */
@Component
public class GitCommitInfoContributor implements InfoContributor {

    private static final String UNKNOWN = "unknown";
    private static final int SHORT_SHA_LENGTH = 7;

    @Value("${RENDER_GIT_COMMIT:}")
    private String renderGitCommit;

    @Value("${RAILWAY_GIT_COMMIT_SHA:}")
    private String railwayGitCommit;

    /** Instante em que a JVM subiu. Fixo enquanto o processo viver. */
    private final Instant startedAt =
            Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    @Override
    public void contribute(Info.Builder builder) {
        // LinkedHashMap e nao Map.of: o valor pode ser nulo quando a propriedade
        // nao esta definida, e Map.of lanca NullPointerException nesse caso.
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("startedAt", startedAt.toString());

        builder.withDetail("git", Map.of("commit", Map.of("id", shortCommit())));
        builder.withDetail("app", app);
    }

    private String shortCommit() {
        String commit = firstNonBlank(renderGitCommit, railwayGitCommit);
        if (commit == null) {
            return UNKNOWN;
        }
        return commit.length() >= SHORT_SHA_LENGTH
                ? commit.substring(0, SHORT_SHA_LENGTH)
                : commit;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
