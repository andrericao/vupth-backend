package com.ospaulos.vupth.infrastructure.config;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Identidade da instancia em execucao, para um deploy poder ser verificado.
 *
 * <p><b>commit</b> vem da variavel que a hospedagem injeta. O Render define
 * {@code RENDER_GIT_COMMIT} sozinho; o Railway define {@code RAILWAY_GIT_COMMIT_SHA}
 * apenas quando o deploy nasce da integracao com o GitHub — num {@code railway up}
 * disparado pelo CI nao ha contexto de git, e ai o valor fica {@code unknown}.
 *
 * <p><b>startedAt</b> existe por causa disso: e a unica marca que nao depende da
 * hospedagem. Diz quando ESTE processo subiu, entao quem verifica um deploy
 * consegue distinguir a instancia nova da anterior mesmo sem commit — basta
 * exigir um inicio posterior ao momento em que o deploy foi disparado. Sem essa
 * distincao, a verificacao passa contra a instancia ANTIGA: durante o rollout a
 * anterior continua respondendo, e {@code /health} responde UP nas duas.
 *
 * <p>Sai nos dois formatos de proposito. O ISO e para quem le; o epoch em
 * milissegundos e para quem compara — um script de CI comparando numero nao
 * depende de conseguir interpretar data, e o {@link Instant#toString()} tem
 * precisao variavel (omite os milissegundos quando sao zero), que e justamente
 * o tipo de detalhe que quebra parse de shell.
 */
@Component
public class GitCommitInfoContributor implements InfoContributor {

    private static final String UNKNOWN = "unknown";
    private static final int SHORT_SHA_LENGTH = 7;

    private final String renderGitCommit;
    private final String railwayGitCommit;

    /**
     * O commit que o CI grava em {@value #BUNDLED_COMMIT_RESOURCE} antes do {@code railway up}. O
     * upload do Railway nao carrega contexto de git — sem este arquivo o commit sai "unknown" e a
     * espera do test-prod nao tinha como distinguir a build nova de um restart da build antiga
     * (17/09: a antiga reiniciou 26s depois do deploy e o Newman a testou como se fosse a nova).
     */
    private final String bundledCommit;

    static final String BUNDLED_COMMIT_RESOURCE = "build-commit.txt";

    /** Inicio da JVM, nao da criacao deste bean. Fixo enquanto o processo viver. */
    private final long startedAtEpochMs;

    /**
     * Quanto ESTA instancia levou para ficar pronta, em milissegundos. {@code null} ate o
     * {@link ApplicationReadyEvent} — quem le o {@code /actuator/info} durante o boot ve o campo
     * ausente, que e a resposta honesta (*"ainda nao ficou pronta"*), e nao um zero enganoso.
     *
     * <p><b>Por que isto existe (D4, 2026-08-15).</b> O Render Free hiberna, e o custo do cold start
     * so tinha uma medicao: 35s, tirada a mao pelo front-partner em 11/08. Numero de uma medicao
     * manual nao sustenta decisao de custo nem prova que uma otimizacao funcionou. Publicando aqui,
     * o tempo de boot passa a ser observavel <b>no ambiente que importa</b> — a CPU compartilhada do
     * Render, nao a maquina de quem desenvolve, onde este numero nao significa nada.
     *
     * <p>{@code volatile} porque quem escreve e a thread do boot e quem le e uma thread de request.
     */
    private volatile Long readyInMs;

    /** O construtor do bean. Anotado porque a classe tem dois e o Spring precisa escolher. */
    @Autowired
    public GitCommitInfoContributor(
            @Value("${RENDER_GIT_COMMIT:}") String renderGitCommit,
            @Value("${RAILWAY_GIT_COMMIT_SHA:}") String railwayGitCommit) {
        this(renderGitCommit, railwayGitCommit, readBundledCommit(),
                ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /**
     * O inicio entra por parametro para o teste conseguir fixar o instante — o
     * relogio da JVM nao e escolha de quem testa.
     */
    public GitCommitInfoContributor(String renderGitCommit, String railwayGitCommit, long startedAtEpochMs) {
        this(renderGitCommit, railwayGitCommit, "", startedAtEpochMs);
    }

    public GitCommitInfoContributor(String renderGitCommit, String railwayGitCommit, String bundledCommit,
                                    long startedAtEpochMs) {
        this.renderGitCommit = renderGitCommit;
        this.railwayGitCommit = railwayGitCommit;
        this.bundledCommit = bundledCommit == null ? "" : bundledCommit.trim();
        this.startedAtEpochMs = startedAtEpochMs;
    }

    private static String readBundledCommit() {
        ClassPathResource resource = new ClassPathResource(BUNDLED_COMMIT_RESOURCE);
        if (!resource.exists()) {
            return "";
        }
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Registra o tempo ate a instancia ficar pronta. O {@link ApplicationReadyEvent} e o marco
     * certo: e o mesmo ponto em que o {@code readiness} passa a responder UP, entao este numero e
     * exatamente o que o portao {@code waitForBackend()} dos apps espera — nao um tempo interno que
     * so faz sentido aqui dentro.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registraTempoDeBoot(ApplicationReadyEvent event) {
        this.readyInMs = event.getTimeTaken().toMillis();
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("git", Map.of("commit", Map.of("id", shortCommit())));

        // LinkedHashMap, nao Map.of: o bootMillis e opcional (ausente ate ficar pronta) e o Map.of
        // nao aceita valor nulo. A ordem estavel tambem mantem o JSON legivel para quem le no olho.
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("startedAt", Instant.ofEpochMilli(startedAtEpochMs).toString());
        // ⚠️ O ci.yml recorta este campo com grep ("startedAtEpochMs":[0-9]*) para decidir se o
        // Newman roda contra a instancia nova. Mantenha-o numero puro e com este nome exato.
        app.put("startedAtEpochMs", startedAtEpochMs);
        if (readyInMs != null) {
            app.put("bootMillis", readyInMs);
        }
        builder.withDetail("app", app);
    }

    /** O commit curto desta build ({@code unknown} sem origem): o release do Sentry (sentry-contexto-e-limpeza). */
    public String commit() {
        return shortCommit();
    }

    private String shortCommit() {
        String commit = firstNonBlank(renderGitCommit, railwayGitCommit, bundledCommit);
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
