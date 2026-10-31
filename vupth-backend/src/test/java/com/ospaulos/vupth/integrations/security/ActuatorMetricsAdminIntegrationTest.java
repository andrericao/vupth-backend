package com.ospaulos.vupth.integrations.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * estudo-desempenho (DEC-147): o {@code /actuator/metrics} so abre para o ADMIN autenticado e so onde a flag liga (staging). Com a
 * flag desligada (producao), nem o ADMIN abre. O endpoint fica exposto nos dois cenarios de proposito: assim o "negado" prova a regra de
 * seguranca e nao a ausencia do endpoint.
 */
class ActuatorMetricsAdminIntegrationTest {

    @SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "vupth.actuator.metrics-admin=true",
            "vupth.actuator.expose-all=false",
            "management.endpoints.web.exposure.include=health,info,metrics"
    })
    @Nested
    class Staging {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;
        @Autowired UserRepository userRepository;
        private final Faker faker = new Faker();

        @Test
        @DisplayName("staging: anonimo 4xx, cliente 403, ADMIN 200; as rotas do http.server.requests e o pool aparecem para o ADMIN")
        void onlyAdminOpens() throws Exception {
            mvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());

            var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            mvc.perform(get("/actuator/metrics").header("Authorization", client.token())).andExpect(status().isForbidden());

            var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
            base.addRole(RoleType.ADMIN);
            userRepository.save(base);
            mvc.perform(get("/actuator/metrics").header("Authorization", admin.token())).andExpect(status().isOk());
            mvc.perform(get("/actuator/metrics/hikaricp.connections.active").header("Authorization", admin.token()))
                    .andExpect(status().isOk());

            // o resto do actuator continua fechado ate para o ADMIN
            mvc.perform(get("/actuator/env").header("Authorization", admin.token())).andExpect(status().is4xxClientError());
        }
    }

    @SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "vupth.actuator.metrics-admin=false",
            "vupth.actuator.expose-all=false",
            "management.endpoints.web.exposure.include=health,info,metrics"
    })
    @Nested
    class Production {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper mapper;
        @Autowired UserRepository userRepository;
        private final Faker faker = new Faker();

        @Test
        @DisplayName("producao (flag desligada): nem o ADMIN abre o metrics (4xx)")
        void closedEvenForAdmin() throws Exception {
            var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
            base.addRole(RoleType.ADMIN);
            userRepository.save(base);
            mvc.perform(get("/actuator/metrics").header("Authorization", admin.token())).andExpect(status().is4xxClientError());
            mvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
        }
    }
}
