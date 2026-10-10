package com.ospaulos.vupth.domain.access.service;

import com.ospaulos.vupth.infrastructure.config.ClusterLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests — a limpeza diaria do registro de acesso (registro-de-acesso). */
class AccessLogPurgeSchedulerTest {

    @Test
    @DisplayName("runCycle: sem trava, direto; com trava, por ela; erro e engolido")
    void runCycle() {
        AccessLogService service = mock(AccessLogService.class);
        AccessLogPurgeScheduler scheduler = new AccessLogPurgeScheduler(service);

        scheduler.runCycle();
        verify(service).purgeExpired();

        ClusterLock lock = mock(ClusterLock.class);
        when(lock.runExclusive(eq("access-log-purge"), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return true;
        });
        ReflectionTestUtils.setField(scheduler, "clusterLock", lock);
        scheduler.runCycle();
        verify(service, times(2)).purgeExpired();

        when(lock.runExclusive(anyString(), any())).thenThrow(new RuntimeException("trava fora"));
        assertThatCode(scheduler::runCycle).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onBoot arma (ou nao, com auto-arm desligado); shutdown sem init nao quebra")
    void lifecycle() {
        AccessLogPurgeScheduler scheduler = new AccessLogPurgeScheduler(mock(AccessLogService.class));
        assertThatCode(scheduler::shutdown).doesNotThrowAnyException();

        scheduler.init();
        ReflectionTestUtils.setField(scheduler, "autoArm", false);
        assertThatCode(scheduler::onBoot).doesNotThrowAnyException();
        ReflectionTestUtils.setField(scheduler, "autoArm", true);
        assertThatCode(scheduler::onBoot).doesNotThrowAnyException();
        scheduler.shutdown();
    }
}
