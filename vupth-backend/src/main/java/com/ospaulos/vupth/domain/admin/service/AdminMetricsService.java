package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Alerts;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Counter;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.HourCount;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Finance;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Funnel;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Health;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Money;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Overview;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Pilot;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Referral;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Series;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.SeriesPoint;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.StatusTotal;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.StuckPayment;
import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Window;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Os numeros do painel (painel-de-monitoramento, fatia 2): saude de agora, funil, dinheiro, series e alertas. Leitura
 * pura por SQL direto, no dia da loja (America/Sao_Paulo). Os atendimentos entram pelo horario marcado
 * ({@code scheduled_time}), como nos relatorios. Sem dado pessoal.
 */
@Service
@Transactional(readOnly = true)
public class AdminMetricsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String SAO_PAULO = "America/Sao_Paulo";


    private final JdbcTemplate jdbc;
    private final Clock clock;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    @org.springframework.beans.factory.annotation.Autowired
    public AdminMetricsService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    AdminMetricsService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ─── overview ───

    /**
     * @param compare {@code same_weekday_last_week} (padrao), {@code previous_period} ou {@code none}
     */
    public Overview overview(OffsetDateTime from, OffsetDateTime to, String compare) {
        Window period = window(from, to);
        Window other = compareWindow(period, compare);
        return new Overview(period, other, health(), funnel(period), other == null ? null : funnel(other),
                money(period), other == null ? null : money(other));
    }

    Window compareWindow(Window period, String compare) {
        if ("none".equalsIgnoreCase(compare)) {
            return null;
        }
        if ("previous_period".equalsIgnoreCase(compare)) {
            Duration length = Duration.between(period.from(), period.to());
            return new Window(period.from().minus(length), period.from());
        }
        return new Window(period.from().minusDays(7), period.to().minusDays(7));
    }

    /** Sem datas: do comeco do dia da loja ate agora. */
    Window window(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime end = to != null ? to : OffsetDateTime.now(clock);
        OffsetDateTime start = from != null ? from : BusinessClock.startOfDay(BusinessClock.dateAt(end));
        return new Window(start, end);
    }

    private Health health() {
        long stores = count("SELECT count(*) FROM establishment WHERE current_open_status = 'OPEN' AND status = 'ACTIVE'");
        long working = count("SELECT count(DISTINCT professional_id) FROM professional_shift WHERE ended_at IS NULL");
        long active = count("SELECT count(*) FROM attendance WHERE status IN ('SCHEDULED','IN_PROGRESS','PAUSED','LATE')");
        return new Health(stores, working, active);
    }

    private Funnel funnel(Window w) {
        Timestamp f = ts(w.from()), t = ts(w.to());
        long signups = signupTimes(w).size();
        long created = count("SELECT count(*) FROM attendance WHERE created_at >= ? AND created_at < ?", f, t);
        return new Funnel(signups, created, byStatus("FINISHED", f, t), byStatus("CANCELLED", f, t),
                byStatus("REMOVED", f, t), byStatus("LATE", f, t));
    }

    /**
     * Os instantes de cadastro no periodo, pelo JPA: {@code base_user.created_at} e um TIMESTAMP sem fuso gravado na
     * hora local da JVM, e so o Hibernate le de volta do mesmo jeito que grava (em SQL direto o fuso desencontrava).
     */
    private List<OffsetDateTime> signupTimes(Window w) {
        return em.createQuery("SELECT u.createdAt FROM BaseUser u WHERE u.createdAt >= :f AND u.createdAt < :t",
                        OffsetDateTime.class)
                .setParameter("f", w.from()).setParameter("t", w.to()).getResultList();
    }

    private long byStatus(String status, Timestamp f, Timestamp t) {
        return count("SELECT count(*) FROM attendance WHERE status = ? AND scheduled_time >= ? AND scheduled_time < ?",
                status, f, t);
    }

    Money money(Window w) {
        Timestamp f = ts(w.from()), t = ts(w.to());
        return jdbc.queryForObject("""
                SELECT coalesce(sum(total_value),0), coalesce(sum(pro_receives),0), coalesce(sum(intermediary_fee),0),
                       coalesce(sum(platform_fee),0),
                       coalesce(sum(total_value) FILTER (WHERE payment_status = 'PENDING'),0),
                       coalesce(sum(total_value) FILTER (WHERE payment_status = 'AWAITING_CONFIRMATION'),0),
                       coalesce(sum(total_value) FILTER (WHERE payment_status = 'PAID'),0)
                  FROM attendance WHERE status = 'FINISHED' AND NOT test AND scheduled_time >= ? AND scheduled_time < ?
                """, (rs, i) -> new Money(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7),
                sum("SELECT coalesce(sum(amount),0) FROM client_penalty_event WHERE created_at >= ? AND created_at < ?", f, t),
                sum("SELECT coalesce(sum(amount),0) FROM client_penalty_event WHERE charged_attendance_id IS NOT NULL "
                        + "AND created_at >= ? AND created_at < ?", f, t)), f, t);
    }

    // ─── series ───

    /**
     * @param metric      {@code cuts} (concluidos), {@code signups} (cadastros) ou {@code revenue} (faturamento dos concluidos)
     * @param granularity {@code hour} ou {@code day} (padrao)
     */
    public Series series(OffsetDateTime from, OffsetDateTime to, String metric, String granularity) {
        Window w = window(from, to);
        String unit = "hour".equalsIgnoreCase(granularity) ? "hour" : "day";
        String name = metric == null ? "cuts" : metric.toLowerCase();
        String sql = switch (name) {
            case "signups" -> null;
            case "revenue" -> "SELECT date_trunc('" + unit + "', scheduled_time AT TIME ZONE '" + SAO_PAULO + "') AS at, "
                    + "coalesce(sum(total_value),0) AS value FROM attendance WHERE status = 'FINISHED' AND NOT test "
                    + "AND scheduled_time >= ? AND scheduled_time < ? GROUP BY 1 ORDER BY 1";
            default -> {
                name = "cuts";
                yield "SELECT date_trunc('" + unit + "', scheduled_time AT TIME ZONE '" + SAO_PAULO + "') AS at, "
                        + "count(*) AS value FROM attendance WHERE status = 'FINISHED' "
                        + "AND scheduled_time >= ? AND scheduled_time < ? GROUP BY 1 ORDER BY 1";
            }
        };
        if (sql == null) {
            return new Series("signups", unit, signupSeries(w, unit));
        }
        List<SeriesPoint> points = jdbc.query(sql, (rs, i) -> new SeriesPoint(
                rs.getTimestamp("at").toLocalDateTime(), rs.getBigDecimal("value")), ts(w.from()), ts(w.to()));
        return new Series(name, unit, points);
    }

    /** Os cadastros por hora ou dia da loja (agrupados aqui, pelo motivo do {@link #signupTimes}). */
    private List<SeriesPoint> signupSeries(Window w, String unit) {
        java.util.TreeMap<java.time.LocalDateTime, Long> buckets = new java.util.TreeMap<>();
        for (OffsetDateTime at : signupTimes(w)) {
            java.time.LocalDateTime local = at.atZoneSameInstant(BusinessClock.zone()).toLocalDateTime();
            java.time.LocalDateTime bucket = "hour".equals(unit)
                    ? local.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                    : local.toLocalDate().atStartOfDay();
            buckets.merge(bucket, 1L, Long::sum);
        }
        return buckets.entrySet().stream()
                .map(e -> new SeriesPoint(e.getKey(), BigDecimal.valueOf(e.getValue()))).toList();
    }

    // ─── finance ───

    public Finance finance(OffsetDateTime from, OffsetDateTime to) {
        Window w = window(from, to);
        Timestamp f = ts(w.from()), t = ts(w.to());
        List<StatusTotal> byStatus = jdbc.query("""
                SELECT coalesce(payment_status, 'NONE') AS status, count(*) AS n, coalesce(sum(total_value),0) AS amount
                  FROM attendance WHERE status = 'FINISHED' AND NOT test AND scheduled_time >= ? AND scheduled_time < ?
                 GROUP BY 1 ORDER BY 1
                """, (rs, i) -> new StatusTotal(rs.getString("status"), rs.getLong("n"), rs.getBigDecimal("amount")), f, t);
        Referral referral = jdbc.queryForObject("""
                SELECT coalesce(sum(amount),0), coalesce(sum(amount) FILTER (WHERE paid_at IS NOT NULL),0),
                       coalesce(sum(amount) FILTER (WHERE paid_at IS NULL),0)
                  FROM referral_credit WHERE created_at >= ? AND created_at < ?
                """, (rs, i) -> new Referral(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)), f, t);
        // DEC-106: os atendimentos de teste ficam fora do dinheiro real e aparecem a parte, como simbolicos
        Pilot pilot = jdbc.queryForObject("""
                SELECT count(*), coalesce(sum(total_value),0) FROM attendance
                 WHERE status = 'FINISHED' AND test AND scheduled_time >= ? AND scheduled_time < ?
                """, (rs, i) -> new Pilot(rs.getLong(1), rs.getBigDecimal(2)), f, t);
        return new Finance(w, money(w), byStatus, referral, pilot);
    }

    // ─── alerts ───

    /** @param hours ha quantas horas o pagamento esta parado para entrar no alerta (padrao 24) */
    public Alerts alerts(int hours) {
        int h = hours <= 0 ? 24 : hours;
        Timestamp limit = ts(OffsetDateTime.now(clock).minusHours(h));
        List<StuckPayment> stuck = jdbc.query("""
                SELECT id, total_value, end_time FROM attendance
                 WHERE status = 'FINISHED' AND NOT test AND payment_status = 'PENDING' AND end_time < ?
                 ORDER BY end_time ASC LIMIT 20
                """, (rs, i) -> new StuckPayment((UUID) rs.getObject("id"), rs.getBigDecimal("total_value"),
                rs.getTimestamp("end_time").toInstant().atOffset(ZoneOffset.UTC)), limit);
        BigDecimal stuckAmount = sum("SELECT coalesce(sum(total_value),0) FROM attendance WHERE status = 'FINISHED' AND NOT test "
                + "AND payment_status = 'PENDING' AND end_time < ?", limit);
        long stuckCount = count("SELECT count(*) FROM attendance WHERE status = 'FINISHED' AND NOT test "
                + "AND payment_status = 'PENDING' AND end_time < ?", limit);
        Timestamp old = ts(OffsetDateTime.now(clock).minusDays(35));
        long unpaid = count("SELECT count(*) FROM referral_credit WHERE paid_at IS NULL AND created_at < ?", old);
        BigDecimal unpaidAmount = sum("SELECT coalesce(sum(amount),0) FROM referral_credit WHERE paid_at IS NULL "
                + "AND created_at < ?", old);
        return new Alerts(h, stuckCount, stuckAmount, stuck, unpaid, unpaidAmount,
                List.of(), counter("PUSH_REFUSED"), counter("HTTP_5XX"));
    }

    /** admin-alertas-push-e-5xx: o contador por hora das ultimas 24 h (sem dado pessoal). */
    private Counter counter(String kind) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime thisHour = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        List<HourCount> byHour = jdbc.query("""
                SELECT hour, n FROM ops_counter WHERE kind = ? AND hour > ? ORDER BY hour ASC
                """, (rs, i) -> new HourCount(rs.getTimestamp("hour").toInstant().atOffset(ZoneOffset.UTC), rs.getLong("n")),
                kind, ts(thisHour.minusHours(24)));
        long lastHour = byHour.stream().filter(c -> c.hour().toInstant().equals(thisHour.toInstant()))
                .mapToLong(HourCount::count).sum();
        long last24h = byHour.stream().mapToLong(HourCount::count).sum();
        return new Counter(lastHour, last24h, byHour);
    }

    // ─── apoio ───

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? ZERO : v;
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }
}
