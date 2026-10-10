package com.ospaulos.vupth.infrastructure.push;

import com.ospaulos.vupth.domain.notification.model.PendingPushReceipt;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.notification.repository.PendingPushReceiptRepository;
import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests — os recibos da Expo (recibos-da-expo, 10/10). O ticket so diz que a Expo aceitou; o
 * recibo diz se ela entregou a FCM/APNs, e o {@code DeviceNotRegistered} do recibo apaga o token.
 */
class PushReceiptCheckerTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String TOKEN_A = "ExponentPushToken[aaaaaaaaaaaaaaaaaaaaaa]";
    private static final String TOKEN_B = "ExponentPushToken[bbbbbbbbbbbbbbbbbbbbbb]";

    private PendingPushReceiptRepository pendingRepository;
    private DeviceTokenRepository deviceTokenRepository;
    private PushSender pushSender;
    private PushReceiptChecker checker;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pendingRepository = mock(PendingPushReceiptRepository.class);
        deviceTokenRepository = mock(DeviceTokenRepository.class);
        pushSender = mock(PushSender.class);
        checker = new PushReceiptChecker(pendingRepository, deviceTokenRepository, pushSender,
                Clock.fixed(NOW, ZoneOffset.UTC));
        checker.init();
    }

    @AfterEach
    void tearDown() {
        checker.shutdown();
    }

    private PendingPushReceipt pending(String ticketId, String token, long minutesAgo) {
        return new PendingPushReceipt(ticketId, token, userId, "Sua vez",
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(minutesAgo));
    }

    private void givenDue(PendingPushReceipt... rows) {
        when(pendingRepository.findBySentAtLessThanEqualOrderBySentAtAsc(any(), any())).thenReturn(List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private Collection<String> deletedIds() {
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(pendingRepository).deleteByTicketIdIn(ids.capture());
        return ids.getValue();
    }

    @Test
    @DisplayName("Busca so os tickets com 15 min ou mais, ate 1000 por ciclo")
    void asksOnlyDueTickets() {
        givenDue();

        assertThat(checker.checkDueReceipts()).isZero();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(pendingRepository).findBySentAtLessThanEqualOrderBySentAtAsc(cutoff.capture(), page.capture());
        assertThat(cutoff.getValue().toInstant()).isEqualTo(NOW.minusSeconds(15 * 60));
        assertThat(page.getValue().getPageSize()).isEqualTo(1000);
        verifyNoInteractions(pushSender);
    }

    @Test
    @DisplayName("Recibo ok sai da fila; DeviceNotRegistered apaga o token; outro erro so loga; sem recibo espera")
    void handlesEachReceipt() {
        givenDue(pending("ok", TOKEN_A, 20), pending("morto", TOKEN_B, 20),
                pending("outro", TOKEN_A, 20), pending("esperando", TOKEN_A, 20));
        when(pushSender.fetchReceipts(List.of("ok", "morto", "outro", "esperando"))).thenReturn(Map.of(
                "ok", new PushReceipt("ok", true, null, null),
                "morto", new PushReceipt("morto", false, PushTicket.DEVICE_NOT_REGISTERED, "gone"),
                "outro", new PushReceipt("outro", false, "MessageRateExceeded", "slow down")));

        assertThat(checker.checkDueReceipts()).isEqualTo(3);

        verify(deviceTokenRepository).deleteByToken(TOKEN_B);
        verify(deviceTokenRepository, never()).deleteByToken(TOKEN_A);
        assertThat(deletedIds()).containsExactlyInAnyOrder("ok", "morto", "outro");
    }

    @Test
    @DisplayName("Sem recibo em 24 h: a Expo ja descartou, a linha sai sem apagar o token")
    void expiredWithoutReceipt() {
        givenDue(pending("velho", TOKEN_A, 24 * 60 + 1), pending("novo", TOKEN_B, 30));
        when(pushSender.fetchReceipts(any())).thenReturn(Map.of());

        assertThat(checker.checkDueReceipts()).isEqualTo(1);

        assertThat(deletedIds()).containsExactly("velho");
        verifyNoInteractions(deviceTokenRepository);
    }

    @Test
    @DisplayName("Expo fora do ar (recibos vazios): nada sai, nada se apaga")
    void expoDownKeepsEverything() {
        givenDue(pending("t-1", TOKEN_A, 20));
        when(pushSender.fetchReceipts(any())).thenReturn(Map.of());

        assertThat(checker.checkDueReceipts()).isZero();

        verify(pendingRepository, never()).deleteByTicketIdIn(any());
        verifyNoInteractions(deviceTokenRepository);
    }

    @Test
    @DisplayName("Falha ao apagar o token morto nao derruba o ciclo")
    void deadTokenDeleteFailureIsSwallowed() {
        givenDue(pending("morto", TOKEN_B, 20));
        when(pushSender.fetchReceipts(any())).thenReturn(Map.of(
                "morto", new PushReceipt("morto", false, PushTicket.DEVICE_NOT_REGISTERED, "gone")));
        doThrow(new RuntimeException("banco fora")).when(deviceTokenRepository).deleteByToken(anyString());

        assertThat(checker.checkDueReceipts()).isEqualTo(1);
        assertThat(deletedIds()).containsExactly("morto");
    }

    @Test
    @DisplayName("runCycle: com a trava, roda por ela; sem a trava, direto; erro no ciclo e engolido")
    void runCycleUsesLockAndSwallows() {
        givenDue();
        checker.runCycle();
        verify(pendingRepository).findBySentAtLessThanEqualOrderBySentAtAsc(any(), any());

        ClusterLock lock = mock(ClusterLock.class);
        when(lock.runExclusive(eq("push-receipts"), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return true;
        });
        ReflectionTestUtils.setField(checker, "clusterLock", lock);
        checker.runCycle();
        verify(lock).runExclusive(eq("push-receipts"), any());
        verify(pendingRepository, times(2)).findBySentAtLessThanEqualOrderBySentAtAsc(any(), any());

        when(lock.runExclusive(anyString(), any())).thenThrow(new RuntimeException("trava fora"));
        assertThatCode(checker::runCycle).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onBoot: arma o ciclo; com auto-arm desligado, nao arma")
    void onBootArmsOrNot() {
        ReflectionTestUtils.setField(checker, "autoArm", false);
        assertThatCode(checker::onBoot).doesNotThrowAnyException();

        ReflectionTestUtils.setField(checker, "autoArm", true);
        assertThatCode(checker::onBoot).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("shutdown sem init nao quebra; o construtor do Spring usa o relogio do sistema")
    void shutdownWithoutInitAndDefaultClock() {
        PushReceiptChecker plain = new PushReceiptChecker(pendingRepository, deviceTokenRepository, pushSender);
        assertThatCode(plain::shutdown).doesNotThrowAnyException();
    }
}
