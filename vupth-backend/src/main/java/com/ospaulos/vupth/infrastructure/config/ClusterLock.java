package com.ospaulos.vupth.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

/**
 * Trava entre instancias (ShedLock, na tabela {@code shedlock} do proprio Postgres). Os timers dos
 * schedulers sao por instancia: com duas no ar, o mesmo ciclo dispararia nas duas. Quem pega a trava
 * roda; a outra pula e tenta de novo mais tarde. A hora vem do banco, nao do relogio de cada maquina.
 */
@Slf4j
@Component
public class ClusterLock {

    /** Teto da trava: se a instancia morrer no meio do ciclo, a trava se solta sozinha depois disto. */
    static final Duration LOCK_AT_MOST_FOR = Duration.ofMinutes(5);

    private final LockingTaskExecutor executor;

    public ClusterLock(DataSource dataSource) {
        this.executor = new DefaultLockingTaskExecutor(new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()));
    }

    /** Roda {@code task} so se esta instancia pegar a trava {@code name}. Devolve se rodou. */
    public boolean runExclusive(String name, Runnable task) {
        try {
            return executor.executeWithLock(() -> {
                task.run();
                return null;
            }, new LockConfiguration(Instant.now(), name, LOCK_AT_MOST_FOR, Duration.ZERO)).wasExecuted();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("[ClusterLock] falha no ciclo " + name, t);
        }
    }
}
