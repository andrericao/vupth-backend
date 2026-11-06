package com.ospaulos.vupth.unit.config;

import com.ospaulos.vupth.infrastructure.config.DatabaseInfoContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseInfoContributorTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(String url) {
        Info.Builder b = new Info.Builder();
        new DatabaseInfoContributor(url).contribute(b);
        return (Map<String, Object>) b.build().get("database");
    }

    @Test
    @DisplayName("info: mostra host, porta e nome do banco, sem usuario, senha nem parametros")
    void masksCredentials() {
        Map<String, Object> db = run("jdbc:postgresql://u:segredo@db.exemplo.com:5432/vupth?user=x&password=y&sslmode=require");
        assertThat(db).containsEntry("host", "db.exemplo.com").containsEntry("port", 5432).containsEntry("name", "vupth");
        assertThat(db.toString()).doesNotContain("segredo").doesNotContain("password").doesNotContain("user");
    }

    @Test
    @DisplayName("info: URL ausente ou fora do padrao vira unknown, sem vazar o texto")
    void unknownWhenBad() {
        assertThat(run("")).containsEntry("host", "unknown").containsEntry("name", "unknown");
        assertThat(run("jdbc:postgresql://host com espaco/db?password=s")).containsEntry("host", "unknown");
        assertThat(run(null)).containsEntry("host", "unknown");
    }
}
