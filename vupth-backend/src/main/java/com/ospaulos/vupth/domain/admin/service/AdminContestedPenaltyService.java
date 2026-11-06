package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminContestedPenaltyRow;
import com.ospaulos.vupth.api.admin.dto.AdminPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** multa-contestavel (F5): as multas contestadas e ainda sem decisao, da contestacao mais nova para a mais antiga. Leitura pura por SQL. */
@Service
@Transactional(readOnly = true)
public class AdminContestedPenaltyService {

    private static final String WHERE = " WHERE pe.contested_at IS NOT NULL AND pe.resolved_at IS NULL ";

    private final JdbcTemplate jdbc;

    public AdminContestedPenaltyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<AdminContestedPenaltyRow> list(int page, int size) {
        int s = Math.min(Math.max(size, 1), AdminReadService.MAX_SIZE), p = Math.max(page, 0);
        long total = jdbc.queryForObject("SELECT count(*) FROM client_penalty_event pe" + WHERE, Long.class);
        List<AdminContestedPenaltyRow> items = jdbc.query("""
                SELECT pe.id, CAST(pe.type AS text) AS type, pe.client_id,
                       trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS client_name,
                       a.establishment_id, e.name AS establishment_name,
                       trim(coalesce(pu.first_name,'') || ' ' || coalesce(pu.last_name,'')) AS accuser_name,
                       pe.amount, pe.created_at, pe.contested_at, pe.contest_reason, coalesce(a.test, false) AS test
                  FROM client_penalty_event pe
                  LEFT JOIN attendance a ON a.id = pe.attendance_id
                  LEFT JOIN base_user u ON u.id = pe.client_id
                  LEFT JOIN base_user pu ON pu.id = a.professional_id
                  LEFT JOIN establishment e ON e.id = a.establishment_id
                """ + WHERE + " ORDER BY pe.contested_at DESC, pe.id LIMIT ? OFFSET ?",
                (rs, i) -> new AdminContestedPenaltyRow(
                        (UUID) rs.getObject("id"), rs.getString("type"), (UUID) rs.getObject("client_id"),
                        rs.getString("client_name"), (UUID) rs.getObject("establishment_id"),
                        rs.getString("establishment_name"), rs.getString("accuser_name"), rs.getBigDecimal("amount"),
                        odt(rs.getTimestamp("created_at")), odt(rs.getTimestamp("contested_at")),
                        rs.getString("contest_reason"), rs.getBoolean("test")),
                s, p * s);
        return new AdminPage<>(items, p, s, total);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
