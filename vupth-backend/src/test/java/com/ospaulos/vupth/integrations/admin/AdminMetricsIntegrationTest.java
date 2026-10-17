package com.ospaulos.vupth.integrations.admin;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** painel, fatia 2: overview, series, finance e alerts (so leitura, cada um com a sua permissao). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminMetricsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AdminPermissionRepository permissions;

    private final Faker faker = new Faker();

    private TestUserUtils.UserAuthAndToken admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    @Test
    @DisplayName("overview: saude, funil e dinheiro de hoje, com a comparacao; o cadastro de agora aparece no funil")
    void overview() throws Exception {
        var owner = admin();
        mvc.perform(get("/admin/overview").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").isNotEmpty())
                .andExpect(jsonPath("$.compare_period.from").isNotEmpty())
                .andExpect(jsonPath("$.health.open_stores").isNumber())
                .andExpect(jsonPath("$.health.professionals_working").isNumber())
                .andExpect(jsonPath("$.funnel.signups").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.funnel_compare.signups").isNumber())
                .andExpect(jsonPath("$.money.gross_revenue").isNumber())
                .andExpect(jsonPath("$.money.penalties_charged").isNumber())
                .andExpect(jsonPath("$.money_compare.app_fee").isNumber());
        mvc.perform(get("/admin/overview").param("compare", "none").header("Authorization", owner.token()))
                .andExpect(jsonPath("$.compare_period").doesNotExist())
                .andExpect(jsonPath("$.funnel_compare").doesNotExist());
        mvc.perform(get("/admin/overview").param("compare", "previous_period")
                        .param("from", "2026-10-01T00:00:00-03:00").param("to", "2026-10-08T00:00:00-03:00")
                        .header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compare_period.from").value(org.hamcrest.Matchers.startsWith("2026-09-24")));
    }

    @Test
    @DisplayName("series: cadastros por hora e por dia; metrica desconhecida cai em cuts")
    void series() throws Exception {
        var owner = admin();
        mvc.perform(get("/admin/series").param("metric", "signups").param("granularity", "hour")
                        .header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("signups"))
                .andExpect(jsonPath("$.granularity").value("hour"))
                .andExpect(jsonPath("$.points[0].value").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mvc.perform(get("/admin/series").param("metric", "revenue").header("Authorization", owner.token()))
                .andExpect(jsonPath("$.metric").value("revenue"))
                .andExpect(jsonPath("$.granularity").value("day"));
        mvc.perform(get("/admin/series").param("metric", "qualquer'; drop table x;--").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("cuts"));
    }

    @Test
    @DisplayName("finance e alerts: formato; permissao FINANCE e ALERTS separadas")
    void financeAndAlerts() throws Exception {
        var owner = admin();
        mvc.perform(get("/admin/finance").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.money.gross_revenue").isNumber())
                .andExpect(jsonPath("$.by_payment_status").isArray())
                .andExpect(jsonPath("$.referral.credits_unpaid").isNumber());
        mvc.perform(get("/admin/alerts").param("hours", "1").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments_stuck_hours").value(1))
                .andExpect(jsonPath("$.payments_stuck").isArray())
                .andExpect(jsonPath("$.not_measured[0]").value("push_refused"));
        mvc.perform(get("/admin/alerts").param("hours", "0").header("Authorization", owner.token()))
                .andExpect(jsonPath("$.payments_stuck_hours").value(24));

        var limited = admin();
        AdminPermissionGrant grant = new AdminPermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setUserId(UUID.fromString(limited.id()));
        grant.setPermission(AdminPermission.ALERTS);
        permissions.save(grant);
        mvc.perform(get("/admin/alerts").header("Authorization", limited.token())).andExpect(status().isOk());
        mvc.perform(get("/admin/finance").header("Authorization", limited.token())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/overview").header("Authorization", limited.token())).andExpect(status().isForbidden());
    }
}
