package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminContestedPaymentRow;
import com.ospaulos.vupth.api.admin.dto.AdminPage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Pagamentos contestados pelo cliente e ainda sem confirmacao (pagamento-declarado-contestavel, F4), da contestacao mais
 * nova para a mais antiga. Leitura pura por SQL.
 */
@Service
@Transactional(readOnly = true)
public class AdminContestedPaymentService {

    private static final String WHERE = " WHERE a.payment_contested_at IS NOT NULL AND a.payment_client_ack_at IS NULL ";

    private final JdbcTemplate jdbc;

    public AdminContestedPaymentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AdminPage<AdminContestedPaymentRow> list(int page, int size) {
        int s = Math.min(Math.max(size, 1), AdminReadService.MAX_SIZE), p = Math.max(page, 0);
        long total = jdbc.queryForObject("SELECT count(*) FROM attendance a" + WHERE, Long.class);
        List<AdminContestedPaymentRow> items = jdbc.query("""
                SELECT a.id, a.client_id, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS client_name,
                       a.establishment_id, e.name AS establishment_name, a.total_value, a.payment_marked_by,
                       a.payment_marked_device, a.payment_confirmed_at, a.payment_contested_at, a.payment_contest_reason,
                       coalesce(a.test, false) AS test
                  FROM attendance a
                  LEFT JOIN base_user u ON u.id = a.client_id
                  LEFT JOIN establishment e ON e.id = a.establishment_id
                """ + WHERE + " ORDER BY a.payment_contested_at DESC, a.id LIMIT ? OFFSET ?",
                (rs, i) -> new AdminContestedPaymentRow(
                        (UUID) rs.getObject("id"), (UUID) rs.getObject("client_id"), rs.getString("client_name"),
                        (UUID) rs.getObject("establishment_id"), rs.getString("establishment_name"),
                        rs.getBigDecimal("total_value"), (UUID) rs.getObject("payment_marked_by"),
                        rs.getString("payment_marked_device"), odt(rs.getTimestamp("payment_confirmed_at")),
                        odt(rs.getTimestamp("payment_contested_at")), rs.getString("payment_contest_reason"),
                        rs.getBoolean("test")),
                s, p * s);
        return new AdminPage<>(items, p, s, total);
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
