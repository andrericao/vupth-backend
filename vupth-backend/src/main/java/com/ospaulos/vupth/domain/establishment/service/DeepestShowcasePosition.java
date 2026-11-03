package com.ospaulos.vupth.domain.establishment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * teto-200-lojas-observado (DEC-176, F13): o maior {@code list_position} ja recebido — o ponto mais fundo da lista a que um
 * cliente chegou. Persistido (o Render Free reinicia e a metrica em memoria volta a zero) numa linha de {@code app_setting},
 * com o maximo atomico no banco: so troca se o novo for maior, e a data e a de quando o recorde foi alcancado.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepestShowcasePosition {

    public static final String KEY = "showcase.max_opened_position";

    public record Deepest(int position, OffsetDateTime reachedAt) {}

    private final JdbcTemplate jdbc;

    /** Guarda {@code position} se for o novo recorde. Falha de banco e logada e nao derruba a leitura da loja. */
    public void record(int position) {
        try {
            jdbc.update("""
                    INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES (?, ?, now())
                    ON CONFLICT (setting_key) DO UPDATE
                      SET setting_value = EXCLUDED.setting_value, updated_at = EXCLUDED.updated_at
                      WHERE CAST(app_setting.setting_value AS INTEGER) < CAST(EXCLUDED.setting_value AS INTEGER)
                    """, KEY, String.valueOf(position));
        } catch (RuntimeException e) {
            log.warn("[Showcase] maior posicao nao gravada: {}", e.getMessage());
        }
    }

    public Optional<Deepest> read() {
        List<Deepest> rows = jdbc.query("SELECT setting_value, updated_at FROM app_setting WHERE setting_key = ?",
                (rs, i) -> new Deepest(Integer.parseInt(rs.getString(1)), rs.getObject(2, OffsetDateTime.class)), KEY);
        return rows.stream().findFirst();
    }
}
