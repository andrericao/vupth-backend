package com.ospaulos.vupth.infrastructure.sentry;

import com.ospaulos.vupth.infrastructure.config.GitCommitInfoContributor;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** sentry-contexto-e-limpeza: o release do Sentry e o commit desta build, para ligar o erro ao commit e marcar regressao. */
@Configuration
public class SentryConfig {

    @Bean
    Sentry.OptionsConfiguration<SentryOptions> sentryRelease(GitCommitInfoContributor git) {
        return options -> {
            String commit = git.commit();
            if (options.getRelease() == null && commit != null && !"unknown".equals(commit)) {
                options.setRelease(commit);
            }
        };
    }
}
