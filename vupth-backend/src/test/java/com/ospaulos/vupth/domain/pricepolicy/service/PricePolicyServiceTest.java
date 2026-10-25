package com.ospaulos.vupth.domain.pricepolicy.service;

import com.ospaulos.vupth.domain.pricepolicy.exception.PricePolicyException;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("politica-de-preco-com-ciencia (DEC-100): reajuste sozinho, aviso, 7 dias e trava")
class PricePolicyServiceTest {

    private final ServiceItemRepository items = mock(ServiceItemRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PushNotificationPublisher push = mock(PushNotificationPublisher.class);
    private final EmailSender mail = mock(EmailSender.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC);
    private PricePolicyService service;

    @BeforeEach
    void setUp() {
        service = new PricePolicyService(items, jdbc, push, mail, clock);
    }

    private ServiceItem legacy(String price) {
        ServiceItem item = new ServiceItem();
        item.setId(UUID.randomUUID());
        item.setCustomName("Corte");
        item.setPrice(new BigDecimal(price));
        return item;
    }

    private ServiceItem current(String proReceives) {
        ServiceItem item = new ServiceItem();
        item.setId(UUID.randomUUID());
        item.setCustomName("Barba");
        BigDecimal pro = new BigDecimal(proReceives);
        item.reprice(PriceSplit.clientPriceFor(pro), pro, pro);
        return item;
    }

    @Test
    @DisplayName("servico legado: o valor antigo vira o que o profissional recebe e o preco do cliente e refeito pela regra")
    void legacyIsRepriced() {
        PricePolicyService.Plan plan = service.plan(legacy("30.00"));

        assertThat(plan.legacy()).isTrue();
        assertThat(plan.proReceives()).isEqualByComparingTo("30.00");
        assertThat(plan.basePrice()).isEqualByComparingTo("30.00");
        assertThat(plan.newPrice()).isEqualByComparingTo(PriceSplit.clientPriceFor(new BigDecimal("30.00")));
        assertThat(plan.newPrice().remainder(BigDecimal.ONE)).isEqualByComparingTo("0.90");
    }

    @Test
    @DisplayName("servico que ja esta na regra de agora nao muda; o de preco velho recalcula sem mexer no que o profissional recebe")
    void currentIsKeptAndStaleIsRecomputed() {
        assertThat(service.plan(current("40.00"))).isNull();

        ServiceItem stale = current("40.00");
        stale.setPrice(new BigDecimal("41.90")); // preco antigo, abaixo do X,90 da regra nova
        PricePolicyService.Plan plan = service.plan(stale);
        assertThat(plan).isNotNull();
        assertThat(plan.legacy()).isFalse();
        assertThat(plan.proReceives()).isEqualByComparingTo("40.00");
        assertThat(plan.newPrice()).isEqualByComparingTo(PriceSplit.clientPriceFor(new BigDecimal("40.00")));

        assertThat(service.plan(legacy("0.00"))).isNull();
    }

    @Test
    @DisplayName("publicar: reajusta so o que precisa, grava uma ciencia de 7 dias por profissional e manda push e e-mail")
    void publishRepricesNotifiesAndOpensTheDeadline() {
        ServiceItem old = legacy("30.00");
        ServiceItem fine = current("40.00");
        when(items.findAll()).thenReturn(List.of(old, fine));
        when(jdbc.queryForObject(eq("SELECT coalesce(max(version), 0) FROM price_policy"), eq(Integer.class))).thenReturn(2);
        UUID pro = UUID.randomUUID();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", pro, "email", "pro@exemplo.com")));

        PricePolicyService.Published result = service.publish("Preços pelo pior caso.", UUID.randomUUID());

        assertThat(result.version()).isEqualTo(3);
        assertThat(result.servicesUpdated()).isEqualTo(1);
        assertThat(result.professionalsNotified()).isEqualTo(1);
        assertThat(old.getProReceives()).isEqualByComparingTo("30.00");
        assertThat(old.getPrice().remainder(BigDecimal.ONE)).isEqualByComparingTo("0.90");
        assertThat(old.getClientPrice()).isEqualByComparingTo(old.getPrice());
        verify(items, times(1)).save(old);
        verify(items, never()).save(fine);
        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO professional_price_ack"),
                eq(pro), eq(3), any(), any());
        verify(push).notifyUser(eq(pro), eq("Nova política de preços"), anyString(),
                eq(Map.of("type", "PRICE_POLICY", "policyVersion", "3")));
        verify(mail).send(eq("pro@exemplo.com"), anyString(), anyString());
    }

    @Test
    @DisplayName("falha no push ou no e-mail nao desfaz a publicacao")
    void notificationFailuresAreSwallowed() {
        when(items.findAll()).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        UUID pro = UUID.randomUUID();
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("id", pro, "email", "pro@exemplo.com")));
        org.mockito.Mockito.doThrow(new IllegalStateException("push fora")).when(push).notifyUser(any(), anyString(), anyString(), any());
        org.mockito.Mockito.doThrow(new IllegalStateException("smtp fora")).when(mail).send(anyString(), anyString(), anyString());

        assertThat(service.publish("x", null).professionalsNotified()).isEqualTo(1);
    }

    @Test
    @DisplayName("prazo vencido sem ciencia: 409 PRICE_POLICY_ACK_REQUIRED com versao e prazo; dentro do prazo ou sem aviso, passa")
    @SuppressWarnings("unchecked")
    void gate() {
        UUID pro = UUID.randomUUID();
        OffsetDateTime deadline = OffsetDateTime.parse("2026-10-22T12:00:00Z");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(pro), any())).thenReturn(List.<Object[]>of(new Object[]{4, deadline}));

        assertThatThrownBy(() -> service.assertAcknowledged(pro))
                .isInstanceOfSatisfying(PricePolicyException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PRICE_POLICY_ACK_REQUIRED");
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getPolicyVersion()).isEqualTo(4);
                    assertThat(e.getDeadlineAt()).isEqualTo(deadline);
                });

        UUID free = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(free), any())).thenReturn(List.of());
        service.assertAcknowledged(free);
        service.assertAcknowledged(null);
    }

    @Test
    @DisplayName("ciencia de versao que nao existe: 404; sem aviso pendente: 409")
    void acknowledgeErrors() {
        UUID pro = UUID.randomUUID();
        when(jdbc.queryForObject(eq("SELECT count(*) FROM price_policy WHERE version = ?"), eq(Integer.class), anyInt()))
                .thenReturn(0);
        assertThatThrownBy(() -> service.acknowledge(pro, 9))
                .isInstanceOfSatisfying(PricePolicyException.class, e -> assertThat(e.getCode()).isEqualTo("PRICE_POLICY_NOT_FOUND"));

        when(jdbc.queryForObject(eq("SELECT count(*) FROM price_policy WHERE version = ?"), eq(Integer.class), anyInt()))
                .thenReturn(1);
        when(jdbc.update(anyString(), any(), any(), anyInt())).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), anyInt())).thenReturn(0);
        assertThatThrownBy(() -> service.acknowledge(pro, 1))
                .isInstanceOfSatisfying(PricePolicyException.class, e -> assertThat(e.getCode()).isEqualTo("PRICE_POLICY_NOTHING_TO_ACK"));
    }
}
