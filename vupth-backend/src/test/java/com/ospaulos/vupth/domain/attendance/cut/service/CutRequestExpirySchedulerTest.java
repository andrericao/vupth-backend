package com.ospaulos.vupth.domain.attendance.cut.service;

import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests do auto-nega dos pedidos de corte ({@link CutRequestExpiryScheduler}), sem subir contexto
 * Spring nem depender de temporizacao real: o ciclo e chamado direto e o timer e lido por reflexao.
 */
class CutRequestExpirySchedulerTest {

    CutRequestRepository repo;
    CutRequestService service;
    CutRequestExpiryScheduler scheduler;

    @BeforeEach
    void setup() {
        repo = mock(CutRequestRepository.class);
        service = mock(CutRequestService.class);
        scheduler = new CutRequestExpiryScheduler(repo, service);
        scheduler.init();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private Object pending() {
        return ReflectionTestUtils.getField(scheduler, "pending");
    }

    private void head(OffsetDateTime expiresAt) {
        CutRequest c = new CutRequest();
        c.setExpiresAt(expiresAt);
        when(repo.findFirstByStatusAndExpiresAtIsNotNullOrderByExpiresAtAsc(CutRequestStatus.PENDING))
                .thenReturn(Optional.of(c));
    }

    @Test
    @DisplayName("sem PENDING: o timer fica ocioso")
    void idle_without_pending() {
        when(repo.findFirstByStatusAndExpiresAtIsNotNullOrderByExpiresAtAsc(CutRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        scheduler.reschedule();

        assertThat(pending()).isNull();
    }

    @Test
    @DisplayName("cabeca no futuro: arma o timer; reagendar cancela o anterior")
    void arms_and_replaces_timer() {
        head(OffsetDateTime.now().plusMinutes(5));

        scheduler.reschedule();
        ScheduledFuture<?> first = (ScheduledFuture<?>) pending();
        scheduler.reschedule();

        assertThat((Object) first).isNotNull();
        assertThat(first.isCancelled()).isTrue();
        assertThat(pending()).isNotNull().isNotSameAs(first);
    }

    @Test
    @DisplayName("cabeca ja vencida: dispara em ~1s e expira")
    void overdue_head_fires_soon() {
        head(OffsetDateTime.now().minusMinutes(1));

        scheduler.reschedule();

        verify(service, timeout(5000).atLeastOnce()).expireDueCutRequests();
    }

    @Test
    @DisplayName("boot com auto-arm: reconstroi o timer a partir do banco")
    void boot_rearms() {
        head(OffsetDateTime.now().plusMinutes(5));

        scheduler.onBoot();

        assertThat(pending()).isNotNull();
    }

    @Test
    @DisplayName("boot com auto-arm desligado: nao consulta o banco")
    void boot_without_auto_arm() {
        ReflectionTestUtils.setField(scheduler, "autoArm", false);

        scheduler.onBoot();

        verifyNoInteractions(repo);
        assertThat(pending()).isNull();
    }

    @Test
    @DisplayName("ciclo sem trava de cluster: expira e reagenda para a proxima cabeca")
    void cycle_without_cluster_lock() {
        when(service.expireDueCutRequests()).thenReturn(2);
        when(repo.findFirstByStatusAndExpiresAtIsNotNullOrderByExpiresAtAsc(CutRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        scheduler.runExpiryCycle();

        verify(service).expireDueCutRequests();
        verify(repo).findFirstByStatusAndExpiresAtIsNotNullOrderByExpiresAtAsc(CutRequestStatus.PENDING);
        assertThat(pending()).isNull();
    }

    @Test
    @DisplayName("falha ao expirar: nao reagenda pela cabeca, arma um retry")
    void cycle_failure_schedules_retry() {
        when(service.expireDueCutRequests()).thenThrow(new IllegalStateException("banco fora"));

        scheduler.runExpiryCycle();

        verifyNoInteractions(repo);
        assertThat(pending()).isNotNull();
    }

    @Test
    @DisplayName("com a trava de cluster: roda o ciclo dentro dela")
    void cycle_with_cluster_lock() {
        ClusterLock lock = mock(ClusterLock.class);
        when(lock.runExclusive(eq("cut-request-expiry"), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return true;
        });
        ReflectionTestUtils.setField(scheduler, "clusterLock", lock);
        when(repo.findFirstByStatusAndExpiresAtIsNotNullOrderByExpiresAtAsc(CutRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        scheduler.runExpiryCycle();

        verify(service).expireDueCutRequests();
        assertThat(pending()).isNull();
    }

    @Test
    @DisplayName("outra instancia tem a trava: nao expira e tenta de novo depois")
    void cycle_lock_taken_retries() {
        ClusterLock lock = mock(ClusterLock.class);
        when(lock.runExclusive(eq("cut-request-expiry"), any())).thenReturn(false);
        ReflectionTestUtils.setField(scheduler, "clusterLock", lock);

        scheduler.runExpiryCycle();

        verifyNoInteractions(service);
        assertThat(pending()).isNotNull();
    }

    @Test
    @DisplayName("shutdown sem init nao quebra")
    void shutdown_without_init() {
        new CutRequestExpiryScheduler(repo, service).shutdown();
    }
}
