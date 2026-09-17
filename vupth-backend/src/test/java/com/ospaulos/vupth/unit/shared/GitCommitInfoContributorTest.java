package com.ospaulos.vupth.unit.shared;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ospaulos.vupth.infrastructure.config.GitCommitInfoContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests — {@link GitCommitInfoContributor}, a identidade que o {@code /actuator/info}
 * publica para um deploy poder ser verificado.
 *
 * <p>Isto nao e teste de cosmetico: o job {@code test-prod} do CI decide se o Newman pode
 * rodar comparando o {@code startedAtEpochMs} desta resposta com o instante em que o deploy
 * foi disparado. Se o campo sumir, mudar de nome ou deixar de ser numero puro, a espera nunca
 * se satisfaz, o {@code test-prod} estoura o tempo e o {@code rollback-prod} reverte um deploy
 * que estava correto — o mesmo estrago de 12/08, so que pelo outro lado.
 *
 * <p>Por isso o primeiro teste aplica o MESMO recorte do script do CI, em vez de olhar o mapa
 * por dentro: e o contrato que existe de verdade.
 */
class GitCommitInfoContributorTest {

    /** Igual ao do ci.yml: {@code grep -o '"startedAtEpochMs":[0-9]*'}. */
    private static final Pattern CI_GREP = Pattern.compile("\"startedAtEpochMs\":([0-9]+)");

    /** 2026-08-12T12:00:00.123Z — milissegundos NAO zerados de proposito (ver o teste da virada). */
    private static final long BOOT = 1_786_536_000_123L;

    private Map<String, Object> detailsOf(String render, String railway, long startedAt) {
        Info.Builder builder = new Info.Builder();
        new GitCommitInfoContributor(render, railway, startedAt).contribute(builder);
        return builder.build().getDetails();
    }

    private Map<String, Object> detailsOf(String render, String railway) {
        return detailsOf(render, railway, BOOT);
    }

    @SuppressWarnings("unchecked")
    private String commitId(Map<String, Object> details) {
        Map<String, Object> git = (Map<String, Object>) details.get("git");
        Map<String, Object> commit = (Map<String, Object>) git.get("commit");
        return (String) commit.get("id");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> app(Map<String, Object> details) {
        return (Map<String, Object>) details.get("app");
    }

    @Nested
    @DisplayName("bootMillis — quanto ESTA instancia levou para ficar pronta (D4, cold start do Render)")
    class BootMillis {

        /** Dispara o mesmo evento que o Spring publica quando a aplicacao fica pronta. */
        private GitCommitInfoContributor prontaEm(long millis) {
            GitCommitInfoContributor contributor = new GitCommitInfoContributor("", "", BOOT);
            ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
            when(event.getTimeTaken()).thenReturn(Duration.ofMillis(millis));
            contributor.registraTempoDeBoot(event);
            return contributor;
        }

        private Map<String, Object> appDe(GitCommitInfoContributor contributor) {
            Info.Builder builder = new Info.Builder();
            contributor.contribute(builder);
            return app(builder.build().getDetails());
        }

        @Test
        @DisplayName("ANTES de ficar pronta o campo esta AUSENTE — nao zero, que seria um boot instantaneo mentiroso")
        void antesDoReady_campoAusente() {
            assertThat(appDe(new GitCommitInfoContributor("", "", BOOT)))
                    .doesNotContainKey("bootMillis");
        }

        @Test
        @DisplayName("depois do ready publica o tempo medido, em milissegundos")
        void depoisDoReady_publicaOTempo() {
            assertThat(appDe(prontaEm(35_000L))).containsEntry("bootMillis", 35_000L);
        }

        @Test
        @DisplayName("sai como numero puro — quem compara e script, e o front le para calibrar o teto do gate")
        void bootMillis_saiComoNumeroPuro() throws Exception {
            String json = new ObjectMapper().writeValueAsString(appDe(prontaEm(35_000L)));

            assertThat(json).containsPattern("\"bootMillis\":\\s*35000");
        }

        @Test
        @DisplayName("publicar o bootMillis NAO quebra o grep do ci.yml — os dois convivem no mesmo bloco")
        void bootMillis_naoQuebraOGrepDoCi() throws Exception {
            String json = new ObjectMapper().writeValueAsString(appDe(prontaEm(35_000L)));

            Matcher matcher = CI_GREP.matcher(json);

            assertThat(matcher.find())
                    .as("campo novo no bloco 'app' cegou o recorte do CI: %s", json)
                    .isTrue();
            assertThat(Long.parseLong(matcher.group(1))).isEqualTo(BOOT);
        }
    }

    @Nested
    @DisplayName("startedAt — o corte que o CI usa para separar a instancia nova da anterior")
    class StartedAt {

        @Test
        @DisplayName("sai como NUMERO puro, no formato que o grep do ci.yml recorta")
        void startedAt_isPlainNumber_matchingCiGrep() throws Exception {
            String json = new ObjectMapper().writeValueAsString(detailsOf("", "", BOOT));

            Matcher matcher = CI_GREP.matcher(json);

            assertThat(matcher.find())
                    .as("o script do CI nao acharia o campo em: %s", json)
                    .isTrue();
            assertThat(Long.parseLong(matcher.group(1))).isEqualTo(BOOT);
        }

        @Test
        @DisplayName("o ISO e o mesmo instante do epoch — dois formatos, um valor")
        void startedAtIso_matchesEpoch() {
            Map<String, Object> app = app(detailsOf("", "", BOOT));

            assertThat(app.get("startedAtEpochMs")).isEqualTo(BOOT);
            assertThat(app.get("startedAt")).isEqualTo(Instant.ofEpochMilli(BOOT).toString());
        }

        @Test
        @DisplayName("nao muda entre chamadas: e o inicio do processo, nao o agora")
        void startedAt_isStableAcrossCalls() {
            GitCommitInfoContributor contributor = new GitCommitInfoContributor("", "", BOOT);

            Info.Builder first = new Info.Builder();
            Info.Builder second = new Info.Builder();
            contributor.contribute(first);
            contributor.contribute(second);

            assertThat(app(second.build().getDetails()).get("startedAtEpochMs"))
                    .isEqualTo(app(first.build().getDetails()).get("startedAtEpochMs"));
        }

        @Test
        @DisplayName("com milissegundos ZERO o ISO encolhe, mas o epoch nao — e por isso que o CI le o epoch")
        void wholeSecond_shrinksIsoButNotEpoch() throws Exception {
            long wholeSecond = 1_786_536_000_000L;
            Map<String, Object> details = detailsOf("", "", wholeSecond);

            // O ISO perde a casa decimal: "12:00:00Z" em vez de "12:00:00.123Z". E exatamente
            // essa variacao de precisao do Instant.toString() que quebrava o parse em shell da
            // primeira versao, deixando STARTED_TS=0 — condicao que nunca se satisfaz.
            assertThat(app(details).get("startedAt")).isEqualTo("2026-08-12T12:00:00Z");
            assertThat(app(detailsOf("", "", BOOT)).get("startedAt"))
                    .isEqualTo("2026-08-12T12:00:00.123Z");

            // O epoch atravessa as duas formas sem mudar de formato.
            Matcher matcher = CI_GREP.matcher(new ObjectMapper().writeValueAsString(details));
            assertThat(matcher.find()).isTrue();
            assertThat(Long.parseLong(matcher.group(1))).isEqualTo(wholeSecond);
        }
    }

    @Nested
    @DisplayName("commit — cada hospedagem injeta a sua variavel, e nenhuma injeta no railway up")
    class Commit {

        @Test
        @DisplayName("Render: encurta para 7 caracteres")
        void render_isShortened() {
            assertThat(commitId(detailsOf("a1e3821cebfb1f05767d7142a2accae6e2567296", "")))
                    .isEqualTo("a1e3821");
        }

        @Test
        @DisplayName("Railway responde quando o do Render vem vazio")
        void railway_isUsedWhenRenderIsBlank() {
            assertThat(commitId(detailsOf("", "9bca566f1e2d3c4b5a6978899aabbccddeeff001")))
                    .isEqualTo("9bca566");
        }

        @Test
        @DisplayName("Render tem precedencia quando as duas existem")
        void render_winsOverRailway() {
            assertThat(commitId(detailsOf("aaaaaaa1111", "bbbbbbb2222"))).isEqualTo("aaaaaaa");
        }

        @Test
        @DisplayName("sem nenhuma das duas — o caso do railway up — vira 'unknown'")
        void noVariable_isUnknown() {
            assertThat(commitId(detailsOf("", ""))).isEqualTo("unknown");
        }

        @Test
        @DisplayName("variavel em branco nao passa por preenchida")
        void blankVariable_isTreatedAsAbsent() {
            assertThat(commitId(detailsOf("   ", "  "))).isEqualTo("unknown");
        }

        @Test
        @DisplayName("railway up: o commit que o CI grava no pacote responde quando nao ha variavel")
        void bundledCommit_isUsedWhenNoVariable() {
            Info.Builder builder = new Info.Builder();
            new GitCommitInfoContributor("", "", "069dc58f00b1c2d3e4f5\n", BOOT).contribute(builder);
            assertThat(commitId(builder.build().getDetails())).isEqualTo("069dc58");
        }

        @Test
        @DisplayName("variavel da hospedagem tem precedencia sobre o commit do pacote")
        void variable_winsOverBundled() {
            Info.Builder builder = new Info.Builder();
            new GitCommitInfoContributor("", "9bca566f1e2d", "069dc58f00b1", BOOT).contribute(builder);
            assertThat(commitId(builder.build().getDetails())).isEqualTo("9bca566");
        }

        @Test
        @DisplayName("sha mais curto que 7 nao estoura o substring")
        void shortSha_doesNotThrow() {
            assertThat(commitId(detailsOf("abc", ""))).isEqualTo("abc");
        }
    }
}
