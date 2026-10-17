package com.ospaulos.vupth.domain.admin.model;

/**
 * O que um usuario do painel pode abrir (painel-de-monitoramento e convite-com-permissoes, fatia 1). Cada rota
 * {@code /admin/**} pede uma delas; o ADMIN sem nenhuma linha em {@code admin_permission} e o dono (o Andre) e
 * pode tudo, e o convidado so abre o que o convite marcou.
 */
public enum AdminPermission {
    OVERVIEW,
    USERS,
    ESTABLISHMENTS,
    FINANCE,
    CAMPAIGNS,
    SUPPORT,
    ALERTS,
    AUDIT,
    ADMIN_USERS;

    /** A permissao que a rota pede, ou {@code null} se a rota nao esta mapeada (so o dono entra). */
    public static AdminPermission forPath(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith("/admin/overview") || path.startsWith("/admin/series")) {
            return OVERVIEW;
        }
        if (path.startsWith("/admin/users-admin") || path.startsWith("/admin/invitations")) {
            return ADMIN_USERS;
        }
        if (path.startsWith("/admin/users")) {
            return USERS;
        }
        if (path.startsWith("/admin/establishments")) {
            return ESTABLISHMENTS;
        }
        if (path.startsWith("/admin/finance") || path.startsWith("/admin/referrals")) {
            return FINANCE;
        }
        if (path.startsWith("/admin/campaigns") || path.startsWith("/admin/exemptions")) {
            return CAMPAIGNS;
        }
        if (path.startsWith("/admin/support")) {
            return SUPPORT;
        }
        if (path.startsWith("/admin/alerts")) {
            return ALERTS;
        }
        if (path.startsWith("/admin/audit")) {
            return AUDIT;
        }
        return null;
    }
}
