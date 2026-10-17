package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminAuditRow;
import com.ospaulos.vupth.api.admin.dto.AdminEstablishmentRow;
import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminUserRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * As listas de leitura do painel (painel-de-monitoramento, fatia 2): usuarios, lojas e a auditoria. Leitura pura,
 * por SQL direto, paginada. Nao devolve telefone, chave Pix, senha nem endereco.
 */
@Service
@Transactional(readOnly = true)
public class AdminReadService {

    static final int MAX_SIZE = 100;

    private final JdbcTemplate jdbc;

    public AdminReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<AdminUserRow> users(String q, int page, int size) {
        int s = clampSize(size), p = Math.max(page, 0);
        String like = like(q);
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM base_user
                 WHERE ?::text IS NULL OR lower(email) LIKE ? OR lower(coalesce(first_name,'') || ' ' || coalesce(last_name,'')) LIKE ?
                """, Long.class, like, like, like);
        List<AdminUserRow> items = jdbc.query("""
                SELECT u.id, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS name, u.email,
                       (SELECT string_agg(r.role_type, ',' ORDER BY r.role_type) FROM user_roles r
                         WHERE r.base_user_id = u.id AND r.is_active) AS roles,
                       coalesce(u.is_active, true) AS active, u.deleted_at IS NOT NULL AS deleted, u.created_at
                  FROM base_user u
                 WHERE ?::text IS NULL OR lower(u.email) LIKE ? OR lower(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) LIKE ?
                 ORDER BY u.created_at DESC NULLS LAST, u.id
                 LIMIT ? OFFSET ?
                """, (rs, i) -> new AdminUserRow(
                (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("email"),
                rs.getString("roles") == null ? List.of() : Arrays.asList(rs.getString("roles").split(",")),
                rs.getBoolean("active"), rs.getBoolean("deleted"), odt(rs.getTimestamp("created_at"))),
                like, like, like, s, p * s);
        return new AdminPage<>(items, p, s, total);
    }

    public AdminPage<AdminEstablishmentRow> establishments(String q, int page, int size) {
        int s = clampSize(size), p = Math.max(page, 0);
        String like = like(q);
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM establishment WHERE ?::text IS NULL OR lower(name) LIKE ?", Long.class, like, like);
        List<AdminEstablishmentRow> items = jdbc.query("""
                SELECT e.id, e.name, e.status, e.current_open_status, e.owner_id, e.created_at,
                       trim(coalesce(b.first_name,'') || ' ' || coalesce(b.last_name,'')) AS owner_name,
                       (SELECT count(*) FROM professional p WHERE p.establishment_id = e.id) AS professionals
                  FROM establishment e
                  LEFT JOIN base_user b ON b.id = e.owner_id
                 WHERE ?::text IS NULL OR lower(e.name) LIKE ?
                 ORDER BY e.created_at DESC, e.id
                 LIMIT ? OFFSET ?
                """, (rs, i) -> new AdminEstablishmentRow(
                (UUID) rs.getObject("id"), rs.getString("name"), rs.getString("status"),
                rs.getString("current_open_status"), (UUID) rs.getObject("owner_id"), rs.getString("owner_name"),
                rs.getLong("professionals"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime()),
                like, like, s, p * s);
        return new AdminPage<>(items, p, s, total);
    }

    public AdminPage<AdminAuditRow> audit(OffsetDateTime from, OffsetDateTime to, int page, int size) {
        int s = clampSize(size), p = Math.max(page, 0);
        Timestamp f = from == null ? null : Timestamp.from(from.toInstant());
        Timestamp t = to == null ? null : Timestamp.from(to.toInstant());
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM admin_audit WHERE (?::timestamptz IS NULL OR created_at >= ?)
                   AND (?::timestamptz IS NULL OR created_at < ?)
                """, Long.class, f, f, t, t);
        List<AdminAuditRow> items = jdbc.query("""
                SELECT a.id, a.admin_id, u.email AS admin_email, a.method, a.path, a.query, a.body, a.status, a.created_at
                  FROM admin_audit a LEFT JOIN base_user u ON u.id = a.admin_id
                 WHERE (?::timestamptz IS NULL OR a.created_at >= ?) AND (?::timestamptz IS NULL OR a.created_at < ?)
                 ORDER BY a.created_at DESC, a.id
                 LIMIT ? OFFSET ?
                """, (rs, i) -> new AdminAuditRow(
                (UUID) rs.getObject("id"), (UUID) rs.getObject("admin_id"), rs.getString("admin_email"),
                rs.getString("method"), rs.getString("path"), rs.getString("query"), rs.getString("body"),
                rs.getInt("status"), odt(rs.getTimestamp("created_at"))),
                f, f, t, t, s, p * s);
        return new AdminPage<>(items, p, s, total);
    }

    private static int clampSize(int size) {
        return size <= 0 ? 20 : Math.min(size, MAX_SIZE);
    }

    /** O termo da busca em minusculas e entre %; vazio vira nulo (sem filtro). */
    static String like(String q) {
        return q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("%", "").replace("_", "") + "%";
    }

    private static OffsetDateTime odt(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
