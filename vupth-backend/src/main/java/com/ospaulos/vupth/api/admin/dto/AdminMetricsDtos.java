package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** As respostas dos numeros do painel (painel-de-monitoramento, fatia 2): overview, series, finance e alerts. */
public final class AdminMetricsDtos {

    private AdminMetricsDtos() {
    }

    public record Window(
            @JsonProperty("from") OffsetDateTime from,
            @JsonProperty("to") OffsetDateTime to) {}

    /** O funil do periodo: quem chegou e o que aconteceu com os atendimentos. */
    public record Funnel(
            @JsonProperty("signups") long signups,
            @JsonProperty("attendances_created") long attendancesCreated,
            @JsonProperty("finished") long finished,
            @JsonProperty("cancelled") long cancelled,
            @JsonProperty("removed") long removed,
            @JsonProperty("late") long late) {}

    /** O dinheiro do periodo: dos atendimentos concluidos e das multas. */
    public record Money(
            @JsonProperty("gross_revenue") BigDecimal grossRevenue,
            @JsonProperty("professionals_net") BigDecimal professionalsNet,
            @JsonProperty("gateway_fee") BigDecimal gatewayFee,
            @JsonProperty("app_fee") BigDecimal appFee,
            @JsonProperty("pending_amount") BigDecimal pendingAmount,
            @JsonProperty("awaiting_amount") BigDecimal awaitingAmount,
            @JsonProperty("paid_amount") BigDecimal paidAmount,
            @JsonProperty("penalties_generated") BigDecimal penaltiesGenerated,
            @JsonProperty("penalties_charged") BigDecimal penaltiesCharged) {}

    /** O estado de agora: lojas abertas, profissionais em turno e atendimentos vivos. */
    public record Health(
            @JsonProperty("open_stores") long openStores,
            @JsonProperty("professionals_working") long professionalsWorking,
            @JsonProperty("active_attendances") long activeAttendances) {}

    public record Overview(
            @JsonProperty("period") Window period,
            @JsonProperty("compare_period") Window comparePeriod,
            @JsonProperty("health") Health health,
            @JsonProperty("funnel") Funnel funnel,
            @JsonProperty("funnel_compare") Funnel funnelCompare,
            @JsonProperty("money") Money money,
            @JsonProperty("money_compare") Money moneyCompare) {}

    public record SeriesPoint(
            @JsonProperty("at") LocalDateTime at,
            @JsonProperty("value") BigDecimal value) {}

    public record Series(
            @JsonProperty("metric") String metric,
            @JsonProperty("granularity") String granularity,
            @JsonProperty("points") List<SeriesPoint> points) {}

    public record StatusTotal(
            @JsonProperty("status") String status,
            @JsonProperty("count") long count,
            @JsonProperty("amount") BigDecimal amount) {}

    public record Referral(
            @JsonProperty("credits_total") BigDecimal creditsTotal,
            @JsonProperty("credits_paid") BigDecimal creditsPaid,
            @JsonProperty("credits_unpaid") BigDecimal creditsUnpaid) {}

    /** Atendimentos de teste (piloto, DEC-106): fora do dinheiro real, valores simbolicos. */
    public record Pilot(
            @JsonProperty("attendances") long attendances,
            @JsonProperty("amount") BigDecimal amount) {}

    public record Finance(
            @JsonProperty("period") Window period,
            @JsonProperty("money") Money money,
            @JsonProperty("by_payment_status") List<StatusTotal> byPaymentStatus,
            @JsonProperty("referral") Referral referral,
            @JsonProperty("pilot") Pilot pilot) {}

    public record StuckPayment(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("finished_at") OffsetDateTime finishedAt) {}

    public record Alerts(
            @JsonProperty("payments_stuck_hours") int paymentsStuckHours,
            @JsonProperty("payments_stuck_count") long paymentsStuckCount,
            @JsonProperty("payments_stuck_amount") BigDecimal paymentsStuckAmount,
            @JsonProperty("payments_stuck") List<StuckPayment> paymentsStuck,
            @JsonProperty("referral_unpaid_count") long referralUnpaidCount,
            @JsonProperty("referral_unpaid_amount") BigDecimal referralUnpaidAmount,
            @JsonProperty("not_measured") List<String> notMeasured) {}
}
