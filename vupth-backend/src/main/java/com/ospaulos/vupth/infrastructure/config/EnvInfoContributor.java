package com.ospaulos.vupth.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * F20 (DEC-181): o {@code /actuator/info} diz em que ambiente a API roda ({@code env: production|staging}), para o
 * app de producao recusar seguir se apontar, por engano, para o staging. Sem faixa visivel no app. O valor vem de
 * {@code APP_ENV}; sem ela, do profile ativo ({@code prod} = production, {@code staging} = staging) e, fora deles,
 * {@code local}. Nenhum segredo: so o nome do ambiente.
 */
@Component
public class EnvInfoContributor implements InfoContributor {

    private final String configured;
    private final Environment environment;

    public EnvInfoContributor(@Value("${vupth.env:}") String configured, Environment environment) {
        this.configured = configured;
        this.environment = environment;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("env", resolve());
    }

    String resolve() {
        if (configured != null && !configured.isBlank()) {
            return configured.trim().toLowerCase();
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            return "production";
        }
        if (environment.acceptsProfiles(Profiles.of("staging"))) {
            return "staging";
        }
        return "local";
    }
}
