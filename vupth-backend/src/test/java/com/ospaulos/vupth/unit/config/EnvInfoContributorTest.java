package com.ospaulos.vupth.unit.config;

import com.ospaulos.vupth.infrastructure.config.EnvInfoContributor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** F20: o campo env do /actuator/info. */
class EnvInfoContributorTest {

    private static Object env(String configured, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        Info.Builder builder = new Info.Builder();
        new EnvInfoContributor(configured, environment).contribute(builder);
        return builder.build().get("env");
    }

    @Test
    @DisplayName("APP_ENV manda (normalizado)")
    void configuredWins() {
        assertThat(env(" Production ", "staging")).isEqualTo("production");
    }

    @Test
    @DisplayName("sem APP_ENV: prod -> production, staging -> staging, o resto -> local")
    void fallsBackToProfile() {
        assertThat(env("", "prod")).isEqualTo("production");
        assertThat(env("", "staging")).isEqualTo("staging");
        assertThat(env("")).isEqualTo("local");
        assertThat(env(null, "test")).isEqualTo("local");
    }
}
