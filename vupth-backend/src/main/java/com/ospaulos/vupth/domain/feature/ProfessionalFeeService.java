package com.ospaulos.vupth.domain.feature;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * chave-profissional-paga-taxa (Andre, 06/11): as taxas do corte em campos separados e o modal unico. Desligada, a taxa e calculada e
 * mostrada riscada, e o profissional nao deve nada; ligada, ele paga. A simulacao do gateway usa a tabela editavel do painel. Multa
 * nao entra aqui.
 */
@Service
@RequiredArgsConstructor
public class ProfessionalFeeService {

    private final AppFeatureService features;
    private final AppFeatureRepository featureRepository;
    private final AppSettingService settings;
    private final JdbcTemplate jdbc;

    /** {@code appPercentFee}: os 2%; {@code roundingFee}: a sobra do arredondamento para X,90; {@code gatewayFeeSimulated}: o Asaas. */
    public record Fees(BigDecimal appPercentFee, BigDecimal roundingFee, BigDecimal gatewayFeeSimulated, boolean active) {
    }

    public record GatewayTable(BigDecimal pixFee, BigDecimal cardFixedFee, BigDecimal cardRate) {
    }

    public record Notice(boolean show, boolean feesActive) {
    }

    public boolean active(UUID professionalId) {
        return features.isEnabled(AppFeatureService.PROFESSIONAL_PAYS_FEE, professionalId);
    }

    @Transactional(readOnly = true)
    public GatewayTable gatewayTable() {
        return new GatewayTable(settings.decimal(AppSettingService.ASAAS_FEE_PIX, PriceSplit.PIX_FEE),
                settings.decimal(AppSettingService.ASAAS_FEE_CARD_FIXED, PriceSplit.CARD_FIXED_FEE),
                settings.decimal(AppSettingService.ASAAS_FEE_CARD_RATE, PriceSplit.CARD_RATE));
    }

    @Transactional
    public GatewayTable setGatewayTable(GatewayTable table, UUID adminId) {
        settings.setDecimal(AppSettingService.ASAAS_FEE_PIX, table.pixFee(), adminId);
        settings.setDecimal(AppSettingService.ASAAS_FEE_CARD_FIXED, table.cardFixedFee(), adminId);
        settings.setDecimal(AppSettingService.ASAAS_FEE_CARD_RATE, table.cardRate(), adminId);
        return gatewayTable();
    }

    /** As taxas do corte; {@code null} em servico legado, sem divisao congelada. */
    public static Fees fees(Attendance a, GatewayTable table, boolean active) {
        if (a.getProReceives() == null || a.getTotalValue() == null) {
            return null;
        }
        BigDecimal price = a.getTotalValue();
        BigDecimal percent = price.multiply(PriceSplit.APP_RATE).setScale(2, RoundingMode.HALF_UP);
        BigDecimal platform = a.getPlatformFee() == null ? BigDecimal.ZERO : a.getPlatformFee();
        BigDecimal rounding = platform.subtract(percent).max(BigDecimal.ZERO);
        BigDecimal card = table.cardFixedFee().add(price.multiply(table.cardRate()));
        BigDecimal gateway = table.pixFee().max(card).setScale(2, RoundingMode.CEILING);
        return new Fees(percent.min(platform), rounding, gateway, active);
    }

    /**
     * O modal unico: aparece quando o estado da cobranca para o profissional mudou desde o ultimo que ele viu e ele ja concluiu um corte
     * depois da mudanca da chave. Sem linha vista, o ultimo estado e "nao paga".
     */
    @Transactional(readOnly = true)
    public Notice notice(UUID professionalId) {
        boolean active = active(professionalId);
        Boolean seen = jdbc.query("SELECT fees_active FROM professional_fee_notice WHERE professional_id = ?",
                rs -> rs.next() ? rs.getBoolean(1) : Boolean.FALSE, professionalId);
        if (Boolean.valueOf(active).equals(seen)) {
            return new Notice(false, active);
        }
        OffsetDateTime changedAt = lastChange();
        boolean cutAfter = changedAt != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM attendance WHERE professional_id = ? AND status = 'FINISHED' AND end_time > ?)",
                Boolean.class, professionalId, Timestamp.from(changedAt.toInstant())));
        return new Notice(cutAfter, active);
    }

    /** O app confirma que mostrou o modal: grava o estado visto. */
    @Transactional
    public void seen(UUID professionalId) {
        jdbc.update("""
                INSERT INTO professional_fee_notice (professional_id, fees_active, seen_at) VALUES (?, ?, now())
                ON CONFLICT (professional_id) DO UPDATE SET fees_active = EXCLUDED.fees_active, seen_at = EXCLUDED.seen_at
                """, professionalId, active(professionalId));
    }

    private OffsetDateTime lastChange() {
        return Stream.of(AppFeatureService.PROFESSIONAL_PAYS_FEE, AppFeatureService.ASAAS_SPLIT)
                .map(k -> featureRepository.findById(k).map(AppFeature::getUpdatedAt).orElse(null))
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }
}
