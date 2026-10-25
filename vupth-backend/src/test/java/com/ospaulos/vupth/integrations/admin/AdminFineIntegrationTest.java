package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** admin-multas: GET /admin/multas lista atraso, falta e saida da fila por tipo, da mais nova para a mais antiga. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminFineIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private final Faker faker = new Faker();

    private void fine(UUID clientId, String type, String amount, String createdAt) {
        jdbc.update("INSERT INTO client_penalty_event (id, client_id, type, eta_minutes, amount, created_at) "
                        + "VALUES (?, ?, ?, 0, CAST(? AS numeric), CAST(? AS timestamptz))",
                UUID.randomUUID(), clientId, type, amount, createdAt);
    }

    @Test
    @DisplayName("lista as multas por tipo, da mais nova para a mais antiga; busca pelo cliente; tipo invalido 400; quem nao e admin 403")
    void listsFinesByType() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID clientId = UUID.fromString(user.id());
        BaseUser base = userRepository.findById(clientId).orElseThrow();
        // o cadastro so pede e-mail e senha: da um nome unico para a busca achar so este cliente
        base.setFirstName("Multa" + UUID.randomUUID().toString().substring(0, 8));
        base.setLastName("Teste");
        userRepository.save(base);
        String name = base.getFirstName() + " " + base.getLastName();

        mvc.perform(get("/admin/multas").header("Authorization", user.token())).andExpect(status().isForbidden());
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        fine(clientId, "LATE_ARRIVAL", "5.00", "2026-01-01T10:00:00Z");
        fine(clientId, "NO_SHOW", "5.00", "2026-01-02T10:00:00Z");
        fine(clientId, "LATE_CANCEL", "3.00", "2026-01-03T10:00:00Z");

        mvc.perform(get("/admin/multas").param("q", name).header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].type").value("LATE_CANCEL"))
                .andExpect(jsonPath("$.items[1].type").value("NO_SHOW"))
                .andExpect(jsonPath("$.items[2].type").value("LATE_ARRIVAL"))
                .andExpect(jsonPath("$.items[0].amount").value(3.0))
                .andExpect(jsonPath("$.items[0].state").value("GENERATED"))
                .andExpect(jsonPath("$.items[0].client_id").value(user.id()))
                .andExpect(jsonPath("$.items[0].client_name").value(name))
                .andExpect(jsonPath("$.items[0].test").value(false));

        mvc.perform(get("/admin/multas").param("type", "no_show").param("q", name).header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].type").value("NO_SHOW"));

        mvc.perform(get("/admin/multas").param("q", "zzz-ninguem-" + UUID.randomUUID()).header("Authorization", user.token()))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/admin/multas").param("type", "PAYMENT_PENDING").param("test", "false")
                        .header("Authorization", user.token()))
                .andExpect(status().isOk());

        mvc.perform(get("/admin/multas").param("type", "OUTRO").header("Authorization", user.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
