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

/** painel, fatia 2: as listas de usuarios, lojas e auditoria (so leitura, paginadas, cada uma com a sua permissao). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminReadIntegrationTest {

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
    @DisplayName("usuarios: busca pelo e-mail devolve a conta com os papeis, sem telefone nem senha; paginacao e teto de 100")
    void users() throws Exception {
        var owner = admin();
        String email = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).email();

        mvc.perform(get("/admin/users").param("q", email.toUpperCase()).header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].email").value(email))
                .andExpect(jsonPath("$.items[0].roles").isArray())
                .andExpect(jsonPath("$.items[0].active").value(true))
                .andExpect(jsonPath("$.items[0].phone").doesNotExist())
                .andExpect(jsonPath("$.items[0].password").doesNotExist());
        mvc.perform(get("/admin/users").param("size", "1000").param("page", "0").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(get("/admin/users").param("q", "nao-existe-" + UUID.randomUUID()).header("Authorization", owner.token()))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    @DisplayName("lojas: a loja criada aparece com o dono; busca pelo nome")
    void establishments() throws Exception {
        var owner = admin();
        var shop = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        mvc.perform(get("/admin/establishments").param("size", "100").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + shop.id() + "')].owner_id").value(owner.id()))
                .andExpect(jsonPath("$.total").isNumber());
        mvc.perform(get("/admin/establishments").param("q", "nao-existe-" + UUID.randomUUID())
                        .header("Authorization", owner.token()))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("auditoria: o pedido anterior aparece com o e-mail do admin; intervalo vazio nao traz nada; AUDIT e exigida")
    void audit() throws Exception {
        var owner = admin();
        mvc.perform(get("/admin/users").header("Authorization", owner.token())).andExpect(status().isOk());

        mvc.perform(get("/admin/audit").param("size", "100").header("Authorization", owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.admin_id=='" + owner.id() + "' && @.path=='/admin/users')].admin_email")
                        .value(owner.email()));
        mvc.perform(get("/admin/audit").param("from", "2999-01-01T00:00:00Z").header("Authorization", owner.token()))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/admin/audit").param("from", "2000-01-01T00:00:00Z").param("to", "2001-01-01T00:00:00Z")
                        .header("Authorization", owner.token()))
                .andExpect(jsonPath("$.total").value(0));

        var limited = admin();
        AdminPermissionGrant grant = new AdminPermissionGrant();
        grant.setId(UUID.randomUUID());
        grant.setUserId(UUID.fromString(limited.id()));
        grant.setPermission(AdminPermission.USERS);
        permissions.save(grant);
        mvc.perform(get("/admin/users").header("Authorization", limited.token())).andExpect(status().isOk());
        mvc.perform(get("/admin/audit").header("Authorization", limited.token())).andExpect(status().isForbidden());
    }
}
