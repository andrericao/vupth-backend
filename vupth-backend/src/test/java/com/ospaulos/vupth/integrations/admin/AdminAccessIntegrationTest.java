package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.admin.model.AdminAudit;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.model.AdminPermissionGrant;
import com.ospaulos.vupth.domain.admin.repository.AdminAuditRepository;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** painel, fatia 1: o dono pode tudo, o convidado so a sua permissao (403 nas outras) e tudo fica na auditoria. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAccessIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AdminPermissionRepository permissions;
    @Autowired AdminAuditRepository audits;

    private final Faker faker = new Faker();

    private TestUserUtils.UserAuthAndToken admin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user;
    }

    private List<AdminAudit> auditOf(String adminId) {
        return audits.findAll().stream().filter(a -> a.getAdminId().toString().equals(adminId)).toList();
    }

    @Test
    @DisplayName("ADMIN sem permissoes (o dono) abre tudo; cada pedido entra na auditoria com o corpo sem segredo")
    void ownerAndAudit() throws Exception {
        var owner = admin();
        mvc.perform(get("/admin/referrals/payouts").header("Authorization", owner.token()))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/campaigns").header("Authorization", owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"password\":\"segredo\"}"))
                .andExpect(status().is4xxClientError());

        List<AdminAudit> rows = auditOf(owner.id());
        assertThat(rows).extracting(AdminAudit::getPath).contains("/admin/referrals/payouts", "/admin/campaigns");
        AdminAudit write = rows.stream().filter(a -> a.getMethod().equals("POST")).findFirst().orElseThrow();
        assertThat(write.getBody()).contains("\"password\":\"***\"").doesNotContain("segredo");
        assertThat(write.getStatus()).isBetween(400, 499);
    }

    @Test
    @DisplayName("com permissoes: so a da rota passa; as outras e as nao mapeadas dao 403 ADMIN_PERMISSION_DENIED, auditado")
    void restricted() throws Exception {
        var invited = admin();
        AdminPermissionGrant grant = new AdminPermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setUserId(UUID.fromString(invited.id()));
        grant.setPermission(AdminPermission.FINANCE);
        permissions.save(grant);

        mvc.perform(get("/admin/referrals/payouts").header("Authorization", invited.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/campaigns").header("Authorization", invited.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_DENIED"));
        mvc.perform(get("/admin/rota-sem-dono").header("Authorization", invited.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_PERMISSION_DENIED"));

        // o status mostra so o que o convidado pode abrir (o painel esconde o resto)
        mvc.perform(get("/admin/auth/status").header("Authorization", invited.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value(false))
                .andExpect(jsonPath("$.permissions.length()").value(1))
                .andExpect(jsonPath("$.permissions[0]").value("FINANCE"));

        assertThat(auditOf(invited.id())).extracting(AdminAudit::getStatus).contains(200, 403, 403);
    }

    @Test
    @DisplayName("quem nao e ADMIN continua com 403 da seguranca, sem auditoria")
    void nonAdmin() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/referrals/payouts").header("Authorization", user.token()))
                .andExpect(status().isForbidden());
        assertThat(auditOf(user.id())).isEmpty();
    }
}
