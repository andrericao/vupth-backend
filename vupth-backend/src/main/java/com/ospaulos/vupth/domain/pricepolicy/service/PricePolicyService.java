package com.ospaulos.vupth.domain.pricepolicy.service;

import com.ospaulos.vupth.domain.pricepolicy.exception.PricePolicyException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * politica-de-preco-com-ciencia (DEC-100). O ADMIN publica a politica de precos: o preco de cada servico e reajustado sozinho para a
 * regra de agora (o profissional recebe o mesmo; o preco do cliente sobe para o menor X,90 que passa na prova real) e cada
 * profissional com servico recebe push e e-mail, com 7 dias para ler e registrar a ciencia. Passado o prazo sem a ciencia, abrir a
 * loja e a fila e barrado ({@link #assertAcknowledged}). Nada acontece sozinho: so o ADMIN publica, e o {@link #preview} mostra o
 * que mudaria antes.
 *
 * <p>Servico legado (sem a divisao gravada): ninguem sabe quanto ele absorveu, entao o valor antigo vira o que o profissional
 * recebe, sem absorver nada, e o preco do cliente e refeito pela regra.</p>
 */
@Slf4j
@Service
public class PricePolicyService {

    public static final int ACK_DAYS = 7;

    private final ServiceItemRepository items;
    private final JdbcTemplate jdbc;
    private final PushNotificationPublisher push;
    private final EmailSender mail;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PricePolicyService(ServiceItemRepository items, JdbcTemplate jdbc, PushNotificationPublisher push,
                              EmailSender mail) {
        this(items, jdbc, push, mail, Clock.systemUTC());
    }

    PricePolicyService(ServiceItemRepository items, JdbcTemplate jdbc, PushNotificationPublisher push, EmailSender mail,
                       Clock clock) {
        this.items = items;
        this.jdbc = jdbc;
        this.push = push;
        this.mail = mail;
        this.clock = clock;
    }

    /** Um servico a reajustar: o preco novo, o que o profissional recebe e o digitado. */
    record Plan(ServiceItem item, BigDecimal newPrice, BigDecimal proReceives, BigDecimal basePrice, boolean legacy) {}

    public record Example(@JsonProperty("service_id") UUID serviceId, @JsonProperty("name") String name,
                          @JsonProperty("old_price") BigDecimal oldPrice, @JsonProperty("new_price") BigDecimal newPrice,
                          @JsonProperty("pro_receives") BigDecimal proReceives, @JsonProperty("legacy") boolean legacy) {}

    public record Preview(@JsonProperty("services_total") int servicesTotal,
                          @JsonProperty("services_to_update") int servicesToUpdate,
                          @JsonProperty("legacy_without_split") int legacyWithoutSplit,
                          @JsonProperty("skipped") int skipped,
                          @JsonProperty("professionals_to_notify") int professionalsToNotify,
                          @JsonProperty("examples") List<Example> examples) {}

    public record Published(@JsonProperty("version") int version, @JsonProperty("published_at") OffsetDateTime publishedAt,
                            @JsonProperty("services_updated") int servicesUpdated,
                            @JsonProperty("professionals_notified") int professionalsNotified) {}

    public record Status(Integer version, OffsetDateTime publishedAt, String summary, boolean acknowledged,
                         OffsetDateTime acknowledgedAt, OffsetDateTime noticedAt, OffsetDateTime deadlineAt,
                         Long daysLeft, boolean blocked) {}

    @Transactional(readOnly = true)
    public Preview preview() {
        List<ServiceItem> all = items.findAll();
        List<Plan> plans = new ArrayList<>();
        int skipped = 0;
        for (ServiceItem item : all) {
            try {
                Plan plan = plan(item);
                if (plan != null) {
                    plans.add(plan);
                }
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        List<Example> examples = plans.stream().limit(5)
                .map(p -> new Example(p.item().getId(), p.item().getCustomName(), p.item().getPrice(), p.newPrice(),
                        p.proReceives(), p.legacy())).toList();
        return new Preview(all.size(), plans.size(), (int) plans.stream().filter(Plan::legacy).count(), skipped,
                professionalsWithServices().size(), examples);
    }

    @Transactional
    public Published publish(String summary, UUID adminId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Integer last = jdbc.queryForObject("SELECT coalesce(max(version), 0) FROM price_policy", Integer.class);
        int version = (last == null ? 0 : last) + 1;

        int updated = 0;
        for (ServiceItem item : items.findAll()) {
            Plan plan;
            try {
                plan = plan(item);
            } catch (RuntimeException e) {
                log.warn("[PricePolicy] servico {} fora da regra, pulado: {}", item.getId(), e.getMessage());
                continue;
            }
            if (plan == null) {
                continue;
            }
            item.reprice(plan.newPrice(), plan.proReceives(), plan.basePrice());
            item.setClientPrice(plan.newPrice());
            items.save(item);
            updated++;
        }

        List<Map<String, Object>> professionals = professionalsWithServices();
        OffsetDateTime deadline = now.plusDays(ACK_DAYS);
        for (Map<String, Object> pro : professionals) {
            UUID id = (UUID) pro.get("id");
            jdbc.update("INSERT INTO professional_price_ack (professional_id, policy_version, notified_at, deadline_at) "
                            + "VALUES (?, ?, ?, ?)", id, version, ts(now), ts(deadline));
        }
        jdbc.update("INSERT INTO price_policy (version, published_at, summary, published_by, services_updated, "
                + "professionals_notified) VALUES (?, ?, ?, ?, ?, ?)",
                version, ts(now), summary.trim(), adminId, updated, professionals.size());
        for (Map<String, Object> pro : professionals) {
            notifyProfessional((UUID) pro.get("id"), (String) pro.get("email"), version, summary.trim());
        }
        log.info("[PricePolicy] versao {} publicada — {} servico(s) reajustado(s), {} profissional(is) avisado(s)",
                version, updated, professionals.size());
        return new Published(version, now, updated, professionals.size());
    }

    /** A ultima politica e a situacao do profissional: ciencia feita, prazo e se ja esta barrado. */
    @Transactional(readOnly = true)
    public Status status(UUID professionalId) {
        List<Status> rows = jdbc.query("""
                SELECT p.version, p.published_at, p.summary, a.acknowledged_at, a.notified_at, a.deadline_at
                  FROM price_policy p
                  LEFT JOIN professional_price_ack a ON a.policy_version = p.version AND a.professional_id = ?
                 ORDER BY p.version DESC LIMIT 1
                """, (rs, i) -> {
            OffsetDateTime ackAt = rs.getObject("acknowledged_at", OffsetDateTime.class);
            OffsetDateTime deadline = rs.getObject("deadline_at", OffsetDateTime.class);
            OffsetDateTime now = OffsetDateTime.now(clock);
            boolean pending = deadline != null && ackAt == null;
            Long left = pending ? Math.max(0, java.time.Duration.between(now, deadline).toDays()) : null;
            return new Status(rs.getInt("version"), rs.getObject("published_at", OffsetDateTime.class),
                    rs.getString("summary"), deadline == null || ackAt != null, ackAt,
                    rs.getObject("notified_at", OffsetDateTime.class), deadline, left,
                    pending && !deadline.isAfter(now));
        }, professionalId);
        return rows.isEmpty() ? new Status(null, null, null, true, null, null, null, null, false) : rows.get(0);
    }

    @Transactional
    public Status acknowledge(UUID professionalId, int version) {
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM price_policy WHERE version = ?", Integer.class, version);
        if (exists == null || exists == 0) {
            throw PricePolicyException.notFound();
        }
        int changed = jdbc.update("UPDATE professional_price_ack SET acknowledged_at = ? WHERE professional_id = ? "
                + "AND policy_version = ? AND acknowledged_at IS NULL", ts(OffsetDateTime.now(clock)), professionalId, version);
        if (changed == 0) {
            Integer already = jdbc.queryForObject("SELECT count(*) FROM professional_price_ack WHERE professional_id = ? "
                    + "AND policy_version = ? AND acknowledged_at IS NOT NULL", Integer.class, professionalId, version);
            if (already == null || already == 0) {
                throw PricePolicyException.nothingToAck();
            }
        }
        return status(professionalId);
    }

    public record Version(@JsonProperty("version") int version, @JsonProperty("published_at") OffsetDateTime publishedAt,
                          @JsonProperty("summary") String summary, @JsonProperty("services_updated") int servicesUpdated,
                          @JsonProperty("professionals_notified") int professionalsNotified,
                          @JsonProperty("acknowledged") long acknowledged, @JsonProperty("pending") long pending) {}

    /** As politicas publicadas, da mais nova para a mais antiga, com quantos ja registraram a ciencia. */
    @Transactional(readOnly = true)
    public List<Version> history() {
        return jdbc.query("""
                SELECT p.version, p.published_at, p.summary, p.services_updated, p.professionals_notified,
                       (SELECT count(*) FROM professional_price_ack a WHERE a.policy_version = p.version
                           AND a.acknowledged_at IS NOT NULL) AS acknowledged,
                       (SELECT count(*) FROM professional_price_ack a WHERE a.policy_version = p.version
                           AND a.acknowledged_at IS NULL) AS pending
                  FROM price_policy p ORDER BY p.version DESC
                """, (rs, i) -> new Version(rs.getInt("version"), utc(rs.getTimestamp("published_at")), rs.getString("summary"),
                rs.getInt("services_updated"), rs.getInt("professionals_notified"), rs.getLong("acknowledged"),
                rs.getLong("pending")));
    }

    /** Barra (409 PRICE_POLICY_ACK_REQUIRED) quem passou do prazo sem registrar a ciencia; nao barra quem nao foi avisado. */
    @Transactional(readOnly = true)
    public void assertAcknowledged(UUID userId) {
        if (userId == null) {
            return;
        }
        List<Object[]> overdue = jdbc.query("""
                SELECT policy_version, deadline_at FROM professional_price_ack
                 WHERE professional_id = ? AND acknowledged_at IS NULL AND deadline_at <= ?
                 ORDER BY deadline_at ASC LIMIT 1
                """, (rs, i) -> new Object[]{rs.getInt("policy_version"), rs.getObject("deadline_at", OffsetDateTime.class)},
                userId, ts(OffsetDateTime.now(clock)));
        if (!overdue.isEmpty()) {
            throw PricePolicyException.ackRequired((Integer) overdue.get(0)[0], (OffsetDateTime) overdue.get(0)[1]);
        }
    }

    /** O reajuste de um servico, ou {@code null} se ja esta na regra de agora. */
    Plan plan(ServiceItem item) {
        if (item.getPrice() == null || item.getPrice().signum() <= 0) {
            return null;
        }
        BigDecimal price = item.getPrice().setScale(2, RoundingMode.HALF_UP);
        boolean legacy = item.getProReceives() == null || item.getBasePrice() == null;
        BigDecimal pro = legacy ? price : item.getProReceives();
        BigDecimal base = legacy ? price : item.getBasePrice();
        BigDecimal expected = PriceSplit.clientPriceFor(pro);
        if (!legacy && expected.compareTo(price) == 0) {
            return null;
        }
        return new Plan(item, expected, pro, base, legacy);
    }

    private List<Map<String, Object>> professionalsWithServices() {
        return jdbc.queryForList("""
                SELECT DISTINCT i.professional_id AS id, u.email AS email
                  FROM service_item i JOIN base_user u ON u.id = i.professional_id
                 WHERE u.deleted_at IS NULL
                """);
    }

    private void notifyProfessional(UUID id, String email, int version, String summary) {
        String body = "Atualizamos a política de preços e os seus serviços foram ajustados sozinhos. Leia e registre a ciência em "
                + ACK_DAYS + " dias para continuar abrindo a loja e a fila.";
        try {
            push.notifyUser(id, "Nova política de preços", body,
                    Map.of("type", "PRICE_POLICY", "policyVersion", String.valueOf(version)));
        } catch (RuntimeException e) {
            log.warn("[PricePolicy] push nao saiu para {}: {}", id, e.getMessage());
        }
        if (email != null && !email.isBlank()) {
            try {
                mail.send(email, "Nova política de preços da VUPTH",
                        body + "\n\nResumo:\n" + summary + "\n\nAbra o app do parceiro, em Perfil, para ler a política e registrar a ciência.");
            } catch (RuntimeException e) {
                log.warn("[PricePolicy] e-mail nao saiu para {}: {}", id, e.getMessage());
            }
        }
    }

    private static Timestamp ts(OffsetDateTime at) {
        return Timestamp.from(at.toInstant());
    }

    static OffsetDateTime utc(Timestamp t) {
        return t == null ? null : t.toInstant().atOffset(ZoneOffset.UTC);
    }
}
