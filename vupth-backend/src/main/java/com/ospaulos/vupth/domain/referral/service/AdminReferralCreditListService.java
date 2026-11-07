package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.api.referral.dto.AdminReferralCreditRow;
import com.ospaulos.vupth.api.referral.dto.ReferralCreditItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * lista-de-premios-no-painel (Andre, 05/11): os premios de indicacao um a um, do mais novo ao mais antigo, para ver quem realmente
 * cortou. Premio de teste fica fora, a nao ser com {@code includeTest}. O periodo vale para a data em que o premio nasceu;
 * {@code personId} acha tanto quem cortou quanto quem recebe. No maximo {@value #MAX_ROWS} linhas.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReferralCreditListService {

    static final int MAX_ROWS = 500;

    private final JdbcTemplate jdbc;

    public enum Status { LOCKED, TO_PAY, PAID }

    public List<AdminReferralCreditRow> list(UUID campaignId, UUID personId, OffsetDateTime from, OffsetDateTime to, Status status,
                                             boolean includeTest) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        if (!includeTest) {
            where.append(" AND NOT c.test");
        }
        if (campaignId != null) {
            where.append(" AND c.campaign_id = ?");
            args.add(campaignId);
        }
        if (personId != null) {
            where.append(" AND (c.beneficiary_id = ? OR a.professional_id = ?)");
            args.add(personId);
            args.add(personId);
        }
        if (from != null) {
            where.append(" AND c.created_at >= ?");
            args.add(Timestamp.from(from.toInstant()));
        }
        if (to != null) {
            where.append(" AND c.created_at < ?");
            args.add(Timestamp.from(to.toInstant()));
        }
        if (status != null) {
            where.append(switch (status) {
                case PAID -> " AND c.paid_at IS NOT NULL";
                case TO_PAY -> " AND c.paid_at IS NULL AND c.released_at IS NOT NULL";
                case LOCKED -> " AND c.paid_at IS NULL AND c.released_at IS NULL";
            });
        }
        args.add(MAX_ROWS);
        return jdbc.query("""
                SELECT c.id, c.campaign_id, cp.name AS campaign_name, a.professional_id,
                       trim(coalesce(p.first_name,'') || ' ' || coalesce(p.last_name,'')) AS professional_name,
                       c.beneficiary_id, trim(coalesce(b.first_name,'') || ' ' || coalesce(b.last_name,'')) AS beneficiary_name,
                       split_part(trim(coalesce(cl.first_name,'')), ' ', 1) AS client_name,
                       c.attendance_id, c.share, c.amount, c.created_at, c.released_at, c.paid_at, c.test
                  FROM referral_credit c
                  LEFT JOIN campaign cp ON cp.id = c.campaign_id
                  LEFT JOIN attendance a ON a.id = c.attendance_id
                  LEFT JOIN base_user p ON p.id = a.professional_id
                  LEFT JOIN base_user b ON b.id = c.beneficiary_id
                  LEFT JOIN base_user cl ON cl.id = c.client_id""" + where + """

                 ORDER BY c.created_at DESC LIMIT ?
                """, (rs, i) -> {
            OffsetDateTime releasedAt = rs.getObject("released_at", OffsetDateTime.class);
            OffsetDateTime paidAt = rs.getObject("paid_at", OffsetDateTime.class);
            return new AdminReferralCreditRow(rs.getObject("id", UUID.class), rs.getObject("campaign_id", UUID.class),
                    rs.getString("campaign_name"), rs.getObject("professional_id", UUID.class), rs.getString("professional_name"),
                    rs.getObject("beneficiary_id", UUID.class), rs.getString("beneficiary_name"), rs.getString("client_name"),
                    rs.getObject("attendance_id", UUID.class), rs.getString("share"), rs.getBigDecimal("amount"),
                    ReferralCreditItemResponse.statusOf(releasedAt, paidAt), rs.getObject("created_at", OffsetDateTime.class),
                    releasedAt, paidAt, rs.getBoolean("test"));
        }, args.toArray());
    }
}
