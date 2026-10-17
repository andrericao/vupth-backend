package com.ospaulos.vupth.infrastructure.admin;

import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

import static org.assertj.core.api.Assertions.assertThat;

/** painel, fatia 1: a permissao de cada rota e os segredos fora da auditoria. */
class AdminAccessFilterTest {

    @Test
    @DisplayName("cada rota do painel pede a sua permissao; rota nao mapeada pede nenhuma (so o dono)")
    void permissionByPath() {
        assertThat(AdminPermission.forPath("/admin/overview")).isEqualTo(AdminPermission.OVERVIEW);
        assertThat(AdminPermission.forPath("/admin/series")).isEqualTo(AdminPermission.OVERVIEW);
        assertThat(AdminPermission.forPath("/admin/users")).isEqualTo(AdminPermission.USERS);
        assertThat(AdminPermission.forPath("/admin/users-admin/x")).isEqualTo(AdminPermission.ADMIN_USERS);
        assertThat(AdminPermission.forPath("/admin/invitations")).isEqualTo(AdminPermission.ADMIN_USERS);
        assertThat(AdminPermission.forPath("/admin/establishments")).isEqualTo(AdminPermission.ESTABLISHMENTS);
        assertThat(AdminPermission.forPath("/admin/finance")).isEqualTo(AdminPermission.FINANCE);
        assertThat(AdminPermission.forPath("/admin/referrals/payouts")).isEqualTo(AdminPermission.FINANCE);
        assertThat(AdminPermission.forPath("/admin/campaigns/1")).isEqualTo(AdminPermission.CAMPAIGNS);
        assertThat(AdminPermission.forPath("/admin/exemptions")).isEqualTo(AdminPermission.CAMPAIGNS);
        assertThat(AdminPermission.forPath("/admin/support/article-votes")).isEqualTo(AdminPermission.SUPPORT);
        assertThat(AdminPermission.forPath("/admin/alerts")).isEqualTo(AdminPermission.ALERTS);
        assertThat(AdminPermission.forPath("/admin/audit")).isEqualTo(AdminPermission.AUDIT);
        assertThat(AdminPermission.forPath("/admin/qualquer-outra")).isNull();
        assertThat(AdminPermission.forPath(null)).isNull();
    }

    @Test
    @DisplayName("senha, token e codigo saem do corpo auditado; o resto fica")
    void redactsSecrets() {
        String body = "{\"name\":\"Promo\",\"password\":\"abc123\",\"Token\":\"xyz\",\"code\":\"482913\",\"value\":5}";

        assertThat(AdminAccessFilter.redact(body))
                .isEqualTo("{\"name\":\"Promo\",\"password\":\"***\",\"Token\":\"***\",\"code\":\"***\",\"value\":5}");
    }

    @Test
    @DisplayName("o corpo da escrita vai para a auditoria (cortado em 2000); leitura sem corpo fica nula")
    void bodyOfWrites() throws Exception {
        MockHttpServletRequest raw = new MockHttpServletRequest("POST", "/admin/campaigns");
        raw.setContent(("{\"name\":\"" + "x".repeat(3000) + "\"}").getBytes());
        ContentCachingRequestWrapper write = new ContentCachingRequestWrapper(raw, AdminAccessFilter.BODY_LIMIT);
        write.getInputStream().readAllBytes();

        assertThat(AdminAccessFilter.body(write)).hasSize(AdminAccessFilter.BODY_LIMIT);
        assertThat(AdminAccessFilter.body(new ContentCachingRequestWrapper(
                new MockHttpServletRequest("GET", "/admin/overview"), AdminAccessFilter.BODY_LIMIT))).isNull();
    }
}
