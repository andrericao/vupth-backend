package com.ospaulos.vupth.domain.campaign.service;

import com.ospaulos.vupth.api.campaign.dto.AdminCampaignFunnel;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * admin-funil-da-campanha: link aberto, conta criada e corte concluido de uma campanha, mais quem resgatou o cupom. Leitura
 * agregada por SQL. A janela e a da campanha, recortada por {@code from}/{@code to}. O clique no link ainda nao e gravado
 * (vem com o compartilhe-e-ganhe), entao o primeiro degrau sai como "nao medido"; os cortes por papel e bairro esperam a
 * tabela de indicacao do estudo.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCampaignFunnelService {

    static final int MAX_REDEEMERS = 100;

    private final CampaignRepository campaignRepository;
    private final JdbcTemplate jdbc;

    public AdminCampaignFunnel funnel(UUID campaignId, OffsetDateTime from, OffsetDateTime to) {
        Campaign campaign = campaignRepository.findById(campaignId).orElseThrow(CampaignException::notFound);
        OffsetDateTime start = from == null || from.isBefore(campaign.getStartsAt()) ? campaign.getStartsAt() : from;
        OffsetDateTime end = to == null || to.isAfter(campaign.getEndsAt()) ? campaign.getEndsAt() : to;

        long accounts = campaign.getType() == CampaignType.REFERRAL_BONUS
                ? count("SELECT count(*) FROM referral WHERE created_at >= ? AND created_at < ?", start, end) : 0;
        long firstCut = count("SELECT count(DISTINCT client_id) FROM referral_credit WHERE campaign_id = ?"
                + " AND created_at >= ? AND created_at < ?", campaignId, start, end);
        long redeemed = count("SELECT count(*) FROM campaign_redemption WHERE campaign_id = ?"
                + " AND redeemed_at >= ? AND redeemed_at < ?", campaignId, start, end);
        List<AdminCampaignFunnel.Redeemer> redeemers = jdbc.query("""
                SELECT trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS name, r.redeemed_at
                  FROM campaign_redemption r LEFT JOIN base_user u ON u.id = r.user_id
                 WHERE r.campaign_id = ? AND r.redeemed_at >= ? AND r.redeemed_at < ?
                 ORDER BY r.redeemed_at DESC LIMIT ?
                """, (rs, i) -> new AdminCampaignFunnel.Redeemer(rs.getString("name"), odt(rs.getTimestamp("redeemed_at"))),
                campaignId, ts(start), ts(end), MAX_REDEEMERS);
        return new AdminCampaignFunnel(campaign.getId(), campaign.getName(), campaign.getType().name(), start, end,
                null, false, accounts, firstCut, redeemed, redeemers);
    }

    private long count(String sql, Object... args) {
        Object[] bound = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            bound[i] = args[i] instanceof OffsetDateTime t ? ts(t) : args[i];
        }
        Long n = jdbc.queryForObject(sql, Long.class, bound);
        return n == null ? 0 : n;
    }

    private static Timestamp ts(OffsetDateTime t) {
        return Timestamp.from(t.toInstant());
    }

    private static OffsetDateTime odt(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
