package com.ospaulos.vupth.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mostra no {@code /actuator/info} QUAL banco esta instancia usa: so o host e o nome, nunca usuario, senha nem
 * parametros da URL. Serve para o Andre conferir que o staging esta no Postgres do Render e a producao no do Railway.
 */
@Component
public class DatabaseInfoContributor implements InfoContributor {

    private final String url;

    public DatabaseInfoContributor(@Value("${spring.datasource.url:}") String url) {
        this.url = url;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("database", describe(url));
    }

    /** Extrai host, porta e nome de uma URL JDBC do Postgres; qualquer coisa fora do padrao vira "unknown". */
    static Map<String, Object> describe(String jdbcUrl) {
        Map<String, Object> db = new LinkedHashMap<>();
        String host = "unknown";
        String name = "unknown";
        Integer port = null;
        try {
            if (jdbcUrl != null && jdbcUrl.startsWith("jdbc:")) {
                URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
                if (uri.getHost() != null) {
                    host = uri.getHost();
                }
                if (uri.getPort() > 0) {
                    port = uri.getPort();
                }
                if (uri.getPath() != null && uri.getPath().length() > 1) {
                    name = uri.getPath().substring(1);
                }
            }
        } catch (IllegalArgumentException ignored) {
            // URL fora do padrao: fica "unknown", sem vazar o texto
        }
        db.put("host", host);
        if (port != null) {
            db.put("port", port);
        }
        db.put("name", name);
        return db;
    }
}
