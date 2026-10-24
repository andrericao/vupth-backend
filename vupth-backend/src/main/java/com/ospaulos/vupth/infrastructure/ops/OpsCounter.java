package com.ospaulos.vupth.infrastructure.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * admin-alertas-push-e-5xx: o contador pequeno por hora que alimenta os alertas do painel. Guarda so o tipo ({@code PUSH_REFUSED},
 * {@code HTTP_5XX}), a hora e o total — nenhum dado pessoal. As linhas com mais de {@value #KEEP_DAYS} dias saem sozinhas, uma
 * vez por hora. Contar nunca derruba quem chama: falha do banco vira so um aviso no log.
 */
@Slf4j
@Component
public class OpsCounter {

    public static final String PUSH_REFUSED = "PUSH_REFUSED";
    public static final String HTTP_5XX = "HTTP_5XX";
    static final int KEEP_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile OffsetDateTime lastPurgeHour;

    @Autowired
    public OpsCounter(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    OpsCounter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public void add(String kind, long amount) {
        if (amount <= 0) {
            return;
        }
        try {
            OffsetDateTime hour = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.HOURS);
            jdbc.update("INSERT INTO ops_counter (hour, kind, n) VALUES (?, ?, ?) "
                    + "ON CONFLICT (hour, kind) DO UPDATE SET n = ops_counter.n + EXCLUDED.n",
                    java.sql.Timestamp.from(hour.toInstant()), kind, amount);
            purgeOnceAnHour(hour);
        } catch (RuntimeException e) {
            log.warn("[OpsCounter] nao deu para contar {}: {}", kind, e.getMessage());
        }
    }

    public void increment(String kind) {
        add(kind, 1);
    }

    private void purgeOnceAnHour(OffsetDateTime hour) {
        if (hour.equals(lastPurgeHour)) {
            return;
        }
        lastPurgeHour = hour;
        jdbc.update("DELETE FROM ops_counter WHERE hour < ?", java.sql.Timestamp.from(hour.minusDays(KEEP_DAYS).toInstant()));
    }
}
