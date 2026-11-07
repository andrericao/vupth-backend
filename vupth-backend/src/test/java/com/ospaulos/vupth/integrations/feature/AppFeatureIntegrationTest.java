package com.ospaulos.vupth.integrations.feature;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.model.AdminPermissionGrant;
import com.ospaulos.vupth.domain.admin.repository.AdminPermissionRepository;
import com.ospaulos.vupth.domain.admin.service.AdminTotpService;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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

import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();
    private String secret;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM app_feature_history");
        jdbc.update("DELETE FROM professional_fee_notice");
        jdbc.update("DELETE FROM app_setting WHERE setting_key LIKE 'asaas.fee.%'");
        attendanceRepository.deleteAll();
        jdbc.update("UPDATE app_feature SET enabled = FALSE, percent = 0, profiles = '', regions = '', accounts = '', updated_at = NULL, updated_by = NULL");
    }

    private TestUserUtils.UserAuthAndToken admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    /** Cadastra o segundo fator do admin; {@link #secret} gera o codigo de cada mudanca. */
    private TestUserUtils.UserAuthAndToken adminWithTotp() throws Exception {
        var user = admin();
        secret = mapper.readTree(mvc.perform(post("/admin/auth/totp/setup").header("Authorization", user.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("secret").asText();
        // com o 2FA cadastrado o /admin/** so aceita o token do painel, que vem no confirm
        String panelToken = mapper.readTree(mvc.perform(post("/admin/auth/totp/confirm").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("access_token").asText();
        return new TestUserUtils.UserAuthAndToken(user.id(), "Bearer " + panelToken, user.email(), user.password());
    }

    private String code() throws Exception {
        var generator = new TimeBasedOneTimePasswordGenerator();
        return String.format("%06d", generator.generateOneTimePassword(
                new SecretKeySpec(AdminTotpService.fromBase32(secret), generator.getAlgorithm()), Instant.now()));
    }

    private String rule(boolean enabled, int percent, String profiles, String regions, String accounts) throws Exception {
        return "{\"enabled\":" + enabled + ",\"percent\":" + percent + ",\"profiles\":[" + profiles + "],\"regions\":[" + regions
                + "],\"accounts\":[" + accounts + "],\"reason\":\"teste\",\"totp\":\"" + code() + "\"}";
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
        String admin = adminWithTotp().token();
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
                .andExpect(jsonPath("$[0].second_factor").value(true))
                .andExpect(jsonPath("$[0].previous.profiles[0]").value("CLIENT"))
                .andExpect(jsonPath("$[0].current.regions[0]").value("SP"))
                .andExpect(jsonPath("$[5].previous.enabled").value(false));
    }

    private void cut(String profId, String establishmentId, String clientId, String clientToken, String profToken, String itemId) throws Exception {
        AttendanceRequest request = new AttendanceRequest(UUID.fromString(profId), UUID.fromString(establishmentId), UUID.fromString(clientId),
                java.util.List.of(UUID.fromString(itemId)), AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(5), 30, OffsetDateTime.now());
        String id = mapper.readTree(mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(patch("/attendances/{id}/start", id).header("Authorization", profToken)).andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", id).header("Authorization", profToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("modal unico: aparece depois do 1o corte apos ligar, some ao confirmar e volta apos desligar; a tabela do Asaas e editavel; o relatorio traz as taxas")
    void feeNotice() throws Exception {
        String admin = adminWithTotp().token();
        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token()).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, pro.token(), establishmentId, "Beto");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        String item = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, pro.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)).id();

        mvc.perform(get("/professional/me/fee-notice").header("Authorization", pro.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.show").value(false))
                .andExpect(jsonPath("$.fees_active").value(false));

        change(admin, SPLIT, rule(true, 100, "", "", "")).andExpect(status().isOk());
        change(admin, FEE, rule(true, 100, "", "", "")).andExpect(status().isOk());
        mvc.perform(get("/professional/me/fee-notice").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.show").value(false))
                .andExpect(jsonPath("$.fees_active").value(true));

        cut(pro.id(), establishmentId, client.id(), client.token(), pro.token(), item);
        mvc.perform(get("/professional/me/fee-notice").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.show").value(true));
        mvc.perform(post("/professional/me/fee-notice/seen").header("Authorization", pro.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/professional/me/fee-notice").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.show").value(false));

        // o relatorio traz as taxas separadas (o servico do teste e legado: da ao corte a divisao congelada)
        jdbc.update("UPDATE attendance SET pro_receives = 30.00, intermediary_fee = 1.99, platform_fee = 0.91 WHERE professional_id = ?",
                UUID.fromString(pro.id()));
        mvc.perform(get("/professional/me/reports").header("Authorization", pro.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuts[0].composition.app_percent_fee").isNumber())
                .andExpect(jsonPath("$.cuts[0].composition.rounding_fee").isNumber())
                .andExpect(jsonPath("$.cuts[0].composition.gateway_fee_simulated").isNumber())
                .andExpect(jsonPath("$.cuts[0].composition.fees_active").value(true));

        // taxas-na-rota-do-recebi: a tela do "Recebi" le as mesmas taxas; o cliente, na mesma rota, nao ve nenhuma
        UUID attendanceId = jdbc.queryForObject("SELECT id FROM attendance WHERE professional_id = ? LIMIT 1", UUID.class, UUID.fromString(pro.id()));
        mvc.perform(get("/attendances/{id}/payment", attendanceId).header("Authorization", pro.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app_percent_fee").isNumber())
                .andExpect(jsonPath("$.rounding_fee").isNumber())
                .andExpect(jsonPath("$.gateway_fee_simulated").isNumber())
                .andExpect(jsonPath("$.fees_active").value(true));
        mvc.perform(get("/attendances/{id}/payment", attendanceId).header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app_percent_fee").doesNotExist())
                .andExpect(jsonPath("$.rounding_fee").doesNotExist())
                .andExpect(jsonPath("$.gateway_fee_simulated").doesNotExist())
                .andExpect(jsonPath("$.fees_active").doesNotExist());

        // desligou: depois do proximo corte o modal volta
        change(admin, FEE, rule(false, 100, "", "", "")).andExpect(status().isOk());
        var second = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        cut(pro.id(), establishmentId, second.id(), second.token(), pro.token(), item);
        mvc.perform(get("/professional/me/fee-notice").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.show").value(true))
                .andExpect(jsonPath("$.fees_active").value(false));

        // a tabela do Asaas: padrao, edicao e validacao
        mvc.perform(get("/admin/settings/asaas-fees").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pix_fee").value(1.99));
        mvc.perform(put("/admin/settings/asaas-fees").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pix_fee\":0,\"card_fixed_fee\":0.49,\"card_rate\":0.0299}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pix_fee").value(0));
        mvc.perform(put("/admin/settings/asaas-fees").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pix_fee\":-1,\"card_fixed_fee\":0.49,\"card_rate\":0.0299}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("artigo: oculto com a chave desligada, PARTIAL ligada para alguem, ALL ligada para todos; publico; a dependencia manda; asaas_split nao tem artigo")
    void articles() throws Exception {
        String admin = adminWithTotp().token();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/features/articles")).andExpect(status().isOk()).andExpect(jsonPath("$", empty()));

        // a chave ligada sem a dependencia, ou sem ninguem, nao mostra o artigo
        change(admin, FEE, rule(true, 100, "\"PROFESSIONAL\"", "", "")).andExpect(status().isOk());
        mvc.perform(get("/features/articles")).andExpect(jsonPath("$", empty()));
        change(admin, SPLIT, rule(true, 0, "", "", "")).andExpect(status().isOk());
        mvc.perform(get("/features/articles")).andExpect(jsonPath("$", empty()));

        // ligada para alguem: parcial
        change(admin, SPLIT, rule(true, 0, "", "", "\"" + client.id() + "\"")).andExpect(status().isOk());
        mvc.perform(get("/features/articles"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].key").value(FEE))
                .andExpect(jsonPath("$[0].article_path").value("recursos/profissional-paga-taxa"))
                .andExpect(jsonPath("$[0].profiles[0]").value("PROFESSIONAL"))
                .andExpect(jsonPath("$[0].release").value("PARTIAL"));

        // para todos, sem faixa; regiao volta a ser parcial; desligou, some
        change(admin, SPLIT, rule(true, 100, "", "", "")).andExpect(status().isOk());
        mvc.perform(get("/features/articles")).andExpect(jsonPath("$[0].release").value("ALL"));
        change(admin, FEE, rule(true, 100, "", "\"SP\"", "")).andExpect(status().isOk());
        mvc.perform(get("/features/articles")).andExpect(jsonPath("$[0].release").value("PARTIAL"));
        change(admin, FEE, rule(false, 100, "", "", "")).andExpect(status().isOk());
        mvc.perform(get("/features/articles")).andExpect(jsonPath("$", empty()));
    }

    @Test
    @DisplayName("sem motivo ou com regra invalida 422; chave que nao existe 404; convidado com permissao e cliente 403; sem login 401")
    void guards() throws Exception {
        String admin = adminWithTotp().token();
        // sem o codigo 403, com o codigo errado 401, e nada muda
        change(admin, SPLIT, "{\"enabled\":true,\"percent\":100,\"profiles\":[],\"regions\":[],\"accounts\":[],\"reason\":\"x\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_CODE_REQUIRED"));
        change(admin, SPLIT, "{\"enabled\":true,\"percent\":100,\"profiles\":[],\"regions\":[],\"accounts\":[],\"reason\":\"x\",\"totp\":\"000000\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ADMIN_TOTP_INVALID"));
        mvc.perform(get("/admin/app-features").header("Authorization", admin))
                .andExpect(jsonPath("$[?(@.key=='" + SPLIT + "')].enabled").value(contains(false)));

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
