package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminFineRow;
import com.ospaulos.vupth.api.admin.dto.AdminPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A lista de multas do painel (admin-multas): atraso, falta, saida da fila e pendencia de pagamento, da mais nova para a mais
 * antiga. Leitura pura por SQL; o filtro de tipo, a busca por cliente ou loja e o filtro de teste ficam no banco.
 */
@Service
@Transactional(readOnly = true)
public class AdminFineService {

    static final Set<String> TYPES = Set.of("LATE_ARRIVAL", "NO_SHOW", "LATE_CANCEL", "PAYMENT_PENDING");

    private static final String FINES = """
            WITH fines AS (
                SELECT pe.id, CAST(pe.type AS text) AS type, pe.client_id, pe.attendance_id, pe.amount, pe.created_at,
                       CASE WHEN ca.status = 'FINISHED' OR pp.status = 'PAID' THEN 'CHARGED' ELSE 'GENERATED' END AS state,
                       a.establishment_id, coalesce(a.test, false) AS test
                  FROM client_penalty_event pe
                  LEFT JOIN attendance a ON a.id = pe.attendance_id
                  LEFT JOIN attendance ca ON ca.id = pe.charged_attendance_id
                  LEFT JOIN penalty_payment pp ON pp.id = pe.penalty_payment_id
                UNION ALL
                SELECT a.id, 'PAYMENT_PENDING', a.client_id, a.id, a.total_value, coalesce(a.end_time, a.created_at),
                       'GENERATED', a.establishment_id, coalesce(a.test, false)
                  FROM attendance a
                 WHERE a.status = 'FINISHED' AND a.payment_status = 'PENDING' AND a.client_id IS NOT NULL
            ), listed AS (
                SELECT f.*, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS client_name,
                       e.name AS establishment_name
                  FROM fines f
                  LEFT JOIN base_user u ON u.id = f.client_id
                  LEFT JOIN establishment e ON e.id = f.establishment_id
            )
            """;

    private static final String FILTER = """
             WHERE (CAST(? AS text) IS NULL OR type = ?)
               AND (CAST(? AS text) IS NULL OR lower(client_name) LIKE ? OR lower(coalesce(establishment_name,'')) LIKE ?)
               AND (CAST(? AS boolean) IS NULL OR test = ?)
            """;

    private final JdbcTemplate jdbc;

    public AdminFineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<AdminFineRow> list(String type, String q, Boolean test, int page, int size) {
        String kind = type == null || type.isBlank() ? null : type.trim().toUpperCase(Locale.ROOT);
        if (kind != null && !TYPES.contains(kind)) {
            throw new IllegalArgumentException(
                    "Tipo de multa invalido: use LATE_ARRIVAL, NO_SHOW, LATE_CANCEL ou PAYMENT_PENDING");
        }
        int s = Math.min(Math.max(size, 1), AdminReadService.MAX_SIZE), p = Math.max(page, 0);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        long total = jdbc.queryForObject(FINES + "SELECT count(*) FROM listed" + FILTER, Long.class,
                kind, kind, like, like, like, test, test);
        List<AdminFineRow> items = jdbc.query(FINES + "SELECT * FROM listed" + FILTER
                        + " ORDER BY created_at DESC NULLS LAST, id LIMIT ? OFFSET ?",
                (rs, i) -> new AdminFineRow(
                        (UUID) rs.getObject("id"), rs.getString("type"), (UUID) rs.getObject("client_id"),
                        rs.getString("client_name"), (UUID) rs.getObject("establishment_id"),
                        rs.getString("establishment_name"), rs.getBigDecimal("amount"), rs.getString("state"),
                        (UUID) rs.getObject("attendance_id"), rs.getBoolean("test"), odt(rs.getTimestamp("created_at"))),
                kind, kind, like, like, like, test, test, s, p * s);
        return new AdminPage<>(items, p, s, total);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
