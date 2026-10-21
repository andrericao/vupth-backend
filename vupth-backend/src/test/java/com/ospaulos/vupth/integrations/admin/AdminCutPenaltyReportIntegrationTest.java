package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.model.AdminPermissionGrant;
import com.ospaulos.vupth.domain.admin.repository.AdminPermissionRepository;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** relatorio-corte-e-multa-por-atendimento (DEC-93): o endpoint do painel, a permissao FINANCE e o vazamento (DEC-94). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminCutPenaltyReportIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AdminPermissionRepository permissions;
    @Autowired AppLedgerRepository ledger;

    private final Faker faker = new Faker();

    private TestUserUtils.UserAuthAndToken admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    @Test
    @DisplayName("devolve o corte e a multa separados, com a taxa do Asaas e o lucro do app, por atendimento e por dia")
    void report() throws Exception {
        var owner = admin();
        UUID attendance = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.parse("2026-03-10T10:00:00-03:00");
        ledger.save(new AppLedgerEntry(attendance, Origin.SERVICE, new BigDecimal("30.90"), new BigDecimal("1.99"),
                new BigDecimal("0.71"), at));
        ledger.save(new AppLedgerEntry(attendance, Origin.PENALTY, new BigDecimal("5.00"), new BigDecimal("0.00"),
                new BigDecimal("5.00"), at));

        mvc.perform(get("/admin/finance/cuts-and-penalties")
                        .param("from", "2026-03-10T00:00:00-03:00").param("to", "2026-03-11T00:00:00-03:00")
                        .header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_by").value("attendance"))
                .andExpect(jsonPath("$.items[0].attendance_id").value(attendance.toString()))
                .andExpect(jsonPath("$.items[0].service.amount").value(30.90))
                .andExpect(jsonPath("$.items[0].service.asaas_fee").value(1.99))
                .andExpect(jsonPath("$.items[0].service.app_profit").value(0.71))
                .andExpect(jsonPath("$.items[0].penalty.amount").value(5.00))
                .andExpect(jsonPath("$.items[0].penalty.app_profit").value(5.00))
                .andExpect(jsonPath("$.items[0].total.app_profit").value(5.71))
                .andExpect(jsonPath("$.totals.attendances").value(1));

        mvc.perform(get("/admin/finance/cuts-and-penalties").param("group_by", "day")
                        .param("from", "2026-03-10T00:00:00-03:00").param("to", "2026-03-11T00:00:00-03:00")
                        .header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].key").value("2026-03-10"))
                .andExpect(jsonPath("$.items[0].attendance_id").doesNotExist());
    }

    @Test
    @DisplayName("so quem tem FINANCE le; o cliente comum recebe 403")
    void permission() throws Exception {
        var limited = admin();
        AdminPermissionGrant grant = new AdminPermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setUserId(UUID.fromString(limited.id()));
        grant.setPermission(AdminPermission.ALERTS);
        permissions.save(grant);
        mvc.perform(get("/admin/finance/cuts-and-penalties").header("Authorization", limited.token()))
                .andExpect(status().isForbidden());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/finance/cuts-and-penalties").header("Authorization", client.token()))
                .andExpect(status().isForbidden());
    }
}
