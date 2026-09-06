package com.ospaulos.vupth.integrations.performance;

import com.ospaulos.vupth.VupthApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test — <b>os indices do caminho quente existem no banco</b>.
 *
 * <p>Ele nasce de um defeito de leitura, nao de um pedido: quatro entidades declaram {@code @Index}
 * e <b>nenhum desses indices existe</b> — com {@code ddl-auto=none}, quem cria schema e o Liquibase,
 * e as anotacoes nunca rodaram. Anotacao <b>parece</b> cobertura; so o banco responde. Por isso a
 * asercao pergunta ao <b>Postgres</b> ({@code pg_indexes}), e nao ao codigo.</p>
 *
 * <p>Confere tambem <b>as colunas e a ordem</b>: um indice com o nome certo e a coluna errada
 * passaria por um teste de existencia e nao serviria a consulta que motivou a migration —
 * em indice composto, prefixo e tudo.</p>
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class)
@ActiveProfiles("test")
class HotPathIndexesIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest(name = "{0} → ({1})")
    @DisplayName("indice do caminho quente existe e cobre as colunas na ordem esperada")
    @CsvSource({
            "idx_attendance_professional_status,        'professional_id, status, scheduled_time'",
            "idx_attendance_establishment_status,       'establishment_id, status'",
            "idx_attendance_client_status,              'client_id, status'",
            "idx_professional_establishment,            'establishment_id'",
            "idx_service_item_professional,             'professional_id'",
            "idx_professional_intervals_professional,   'professional_id'",
            "idx_establishment_addresses_establishment, 'establishment_id'"
    })
    void hotPathIndexExists(String indexName, String columns) {
        List<String> found = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                String.class, indexName);

        assertThat(found)
                .as("o indice %s nao existe no banco — a migration 99-performance/001 nao rodou", indexName)
                .hasSize(1);

        String definition = found.get(0);
        log.info("[Indices] {} → {}", indexName, definition);

        // A definicao do Postgres traz as colunas entre parenteses, na ordem do indice.
        String indexed = definition.substring(definition.lastIndexOf('(') + 1, definition.lastIndexOf(')'));
        List<String> expected = List.of(columns.split("\\s*,\\s*"));
        List<String> actual = List.of(indexed.split("\\s*,\\s*"));

        assertThat(actual)
                .as("em indice composto o PREFIXO e o que a consulta usa — ordem importa")
                .containsExactlyElementsOf(expected);
    }
}
