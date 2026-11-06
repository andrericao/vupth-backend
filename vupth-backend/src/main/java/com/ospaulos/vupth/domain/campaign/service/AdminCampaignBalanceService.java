package com.ospaulos.vupth.domain.campaign.service;

import com.ospaulos.vupth.api.campaign.dto.AdminCampaignBalanceRow;
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
 * balanco-por-campanha (Andre, 06/11): premios gerados, travados, a pagar e pagos de cada campanha. Soma a bonificacao por link e o
 * credito de cupom, que nao tem trava (nasce a pagar). Premio de teste fica fora. O periodo filtra pela data em que o premio nasceu.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCampaignBalanceService {

    private final JdbcTemplate jdbc;

    public List<AdminCampaignBalanceRow> balance(UUID campaignId, OffsetDateTime from, OffsetDateTime to) {
        List<Object> args = new ArrayList<>();
        StringBuilder period = new StringBuilder();
        if (from != null) {
            period.append(" AND p.created_at >= ?");
            args.add(Timestamp.from(from.toInstant()));
        }
        if (to != null) {
            period.append(" AND p.created_at < ?");
            args.add(Timestamp.from(to.toInstant()));
        }
        String campaign = "";
        if (campaignId != null) {
            campaign = " WHERE c.id = ?";
            args.add(campaignId);
        }
        return jdbc.query("""
                WITH p AS (
                  SELECT campaign_id, amount, created_at, released_at, paid_at FROM referral_credit WHERE NOT test
                  UNION ALL
                  SELECT campaign_id, amount, created_at, created_at, paid_at FROM coupon_credit
                )
                SELECT c.id, c.name, c.type, count(p.amount) AS credits,
                       coalesce(sum(p.amount),0) AS generated,
                       coalesce(sum(p.amount) FILTER (WHERE p.released_at IS NULL AND p.paid_at IS NULL),0) AS locked,
                       coalesce(sum(p.amount) FILTER (WHERE p.released_at IS NOT NULL AND p.paid_at IS NULL),0) AS to_pay,
                       coalesce(sum(p.amount) FILTER (WHERE p.paid_at IS NOT NULL),0) AS paid
                  FROM campaign c LEFT JOIN p ON p.campaign_id = c.id""" + period + campaign + """

                 GROUP BY c.id, c.name, c.type
                 ORDER BY generated DESC, c.name
                """, (rs, i) -> new AdminCampaignBalanceRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("type"),
                rs.getLong("credits"), rs.getBigDecimal("generated"), rs.getBigDecimal("locked"), rs.getBigDecimal("to_pay"),
                rs.getBigDecimal("paid")), args.toArray());
    }
}
