package com.ospaulos.vupth.infrastructure.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Cifra o que ja estava em texto puro (S4, fase 1: chave Pix e titular). Roda a cada partida e so toca as
 * linhas ainda sem o prefixo {@code enc:v1:}; depois da primeira vez nao sobra nada. Por SQL direto: o
 * Hibernate nao regravaria um valor que ele ve igual. Sem chave configurada, nao faz nada.
 */
@Slf4j
@Component
public class PersonalDataEncryptionMigrator {

    private final JdbcTemplate jdbc;
    private final DataCipher cipher;

    public PersonalDataEncryptionMigrator(JdbcTemplate jdbc, DataCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onBoot() {
        int done = migrate();
        if (done > 0) {
            log.info("[S4] {} conta(s) com chave Pix/titular cifradas", done);
        }
    }

    /** Cifra as linhas de {@code base_user} ainda em texto puro; devolve quantas. */
    public int migrate() {
        if (!cipher.enabled()) {
            return 0;
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, pix_key, pix_holder_name FROM base_user
                 WHERE (pix_key IS NOT NULL AND pix_key NOT LIKE 'enc:v1:%')
                    OR (pix_holder_name IS NOT NULL AND pix_holder_name NOT LIKE 'enc:v1:%')
                """);
        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE base_user SET pix_key = ?, pix_holder_name = ? WHERE id = ?",
                    cipher.encrypt((String) row.get("pix_key")),
                    cipher.encrypt((String) row.get("pix_holder_name")),
                    row.get("id"));
        }
        return rows.size();
    }
}
