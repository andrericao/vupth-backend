package com.ospaulos.vupth.integrations.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.model.AdminPermissionGrant;
import com.ospaulos.vupth.domain.admin.repository.AdminPermissionRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** recursos-infraestrutura (Andre, 06/11): nasce desligada, so o dono muda, com motivo e historico, e a regra vale por conta. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppFeatureIntegrationTest {

    private static final String SPLIT = "asaas_split";
    private static final String FEE = "profissional_paga_taxa";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AdminPermissionRepository permissions;
    @Autowired JdbcTemplate jdbc;

    private final Faker faker = new Faker();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_feature_history");
        jdbc.update("UPDATE app_feature SET enabled = FALSE, percent = 0, profiles = '', regions = '', accounts = '', updated_at = NULL, updated_by = NULL");
    }

    private TestUserUtils.UserAuthAndToken admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    private static String rule(boolean enabled, int percent, String profiles, String regions, String accounts) {
        return "{\"enabled\":" + enabled + ",\"percent\":" + percent + ",\"profiles\":[" + profiles + "],\"regions\":[" + regions
                + "],\"accounts\":[" + accounts + "],\"reason\":\"teste\"}";
    }

    private ResultActions change(String token, String key, String body) throws Exception {
        return mvc.perform(put("/admin/app-features/{key}", key).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions mine(String token) throws Exception {
        return mvc.perform(get("/features/me").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("as chaves nascem desligadas; lista de contas, porcentagem, perfil e dependencia decidem por conta; o historico guarda o valor anterior")
    void rules() throws Exception {
        String admin = admin().token();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        mvc.perform(get("/admin/app-features").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='" + SPLIT + "')].enabled").value(contains(false)))
                .andExpect(jsonPath("$[?(@.key=='" + FEE + "')].depends_on").value(contains(SPLIT)));
        mine(client.token()).andExpect(jsonPath("$.enabled", empty()));

        // ligada a 0%: so quem esta na lista
        change(admin, SPLIT, rule(true, 0, "", "", "\"" + client.id() + "\"")).andExpect(status().isOk());
        mine(client.token()).andExpect(jsonPath("$.enabled", contains(SPLIT)));
        mine(other).andExpect(jsonPath("$.enabled", empty()));

        // a 100%: todos; a dependente so vale com a de cima
        change(admin, SPLIT, rule(true, 100, "", "", "")).andExpect(status().isOk());
        mine(other).andExpect(jsonPath("$.enabled", contains(SPLIT)));
        change(admin, FEE, rule(true, 100, "", "", "")).andExpect(status().isOk());
        mine(other).andExpect(jsonPath("$.enabled", containsInAnyOrder(SPLIT, FEE)));
        change(admin, SPLIT, rule(false, 100, "", "", "")).andExpect(status().isOk());
        mine(other).andExpect(jsonPath("$.enabled", empty()));

        // perfil e regiao recortam; conta sem endereco fica fora da regiao
        change(admin, SPLIT, rule(true, 100, "\"PROFESSIONAL\"", "", "")).andExpect(status().isOk());
        mine(other).andExpect(jsonPath("$.enabled", empty()));
        change(admin, SPLIT, rule(true, 100, "\"CLIENT\"", "", "")).andExpect(status().isOk());
        mine(other).andExpect(jsonPath("$.enabled", contains(SPLIT, FEE)));
        change(admin, SPLIT, rule(true, 100, "", "\"SP\"", "")).andExpect(status().isOk());
        mine(other).andExpect(jsonPath("$.enabled", empty()));

        mvc.perform(get("/admin/app-features/{key}/history", SPLIT).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].reason").value("teste"))
                .andExpect(jsonPath("$[0].previous.profiles[0]").value("CLIENT"))
                .andExpect(jsonPath("$[0].current.regions[0]").value("SP"))
                .andExpect(jsonPath("$[5].previous.enabled").value(false));
    }

    @Test
    @DisplayName("sem motivo ou com regra invalida 422; chave que nao existe 404; convidado com permissao e cliente 403; sem login 401")
    void guards() throws Exception {
        String admin = admin().token();
        change(admin, SPLIT, "{\"enabled\":true,\"percent\":10,\"profiles\":[],\"regions\":[],\"accounts\":[]}")
                .andExpect(status().isUnprocessableEntity());
        change(admin, SPLIT, rule(true, 101, "", "", "")).andExpect(status().isUnprocessableEntity());
        change(admin, SPLIT, rule(true, 10, "\"ADMIN\"", "", "")).andExpect(status().isUnprocessableEntity());
        change(admin, "nao_existe", rule(true, 10, "", "", ""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APP_FEATURE_NOT_FOUND"));

        var invited = admin();
        for (AdminPermission permission : AdminPermission.values()) {
            AdminPermissionGrant grant = new AdminPermissionGrant();
            grant.setId(UUID.randomUUID());
            grant.setUserId(UUID.fromString(invited.id()));
            grant.setPermission(permission);
            permissions.save(grant);
        }
        mvc.perform(get("/admin/app-features").header("Authorization", invited.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_DENIED"));
        change(invited.token(), SPLIT, rule(true, 100, "", "", "")).andExpect(status().isForbidden());

        String client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        mvc.perform(get("/admin/app-features").header("Authorization", client)).andExpect(status().isForbidden());
        mvc.perform(get("/features/me")).andExpect(status().isUnauthorized());
    }
}
