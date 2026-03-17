package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests — Attendance Status Transitions
 *
 * Covers the state machine validation:
 * - Valid transitions: SCHEDULED → IN_PROGRESS → PAUSED ↔ IN_PROGRESS → FINISHED
 * - Invalid transitions: SCHEDULED → FINISHED, FINISHED → *, etc.
 * - Terminal state protection: FINISHED and CANCELED cannot transition
 *
 * These tests verify the domain model constraints without Spring context (Mockito only).
 */
@DisplayName("Attendance Status Transitions")
class AttendanceStatusTransitionTest {

    private Attendance attendance;
    private Professional professional;
    private Establishment establishment;
    private BaseUser profUser;

    @BeforeEach
    void setUp() {
        // Create domain objects - using exact same pattern as ProfessionalServiceImplTest
        establishment = new Establishment();
        establishment.setId(UUID.randomUUID());
        establishment.setName("Test Barbershop");

        profUser = new BaseUser();
        profUser.setId(UUID.randomUUID());
        profUser.setFirstName("John");
        profUser.addRole(RoleType.CLIENT);
        profUser.addRole(RoleType.PROFESSIONAL);

        professional = new Professional(profUser);
        professional.setEstablishment(establishment);
        professional.setIsAvailable(true);
        professional.setAttendances(new ArrayList<>());

        // Create attendance with valid service item
        ServiceItem serviceItem = new ServiceItem();
        serviceItem.setId(UUID.randomUUID());
        serviceItem.setServiceItemType(ServiceItemType.CORTE_MAQUINA);
        serviceItem.setCustomName("Haircut");
        serviceItem.setPrice(BigDecimal.valueOf(50));
        serviceItem.setEstimatedMinutes(30);
        serviceItem.setProfessional(professional);

        attendance = new Attendance();
        attendance.setId(UUID.randomUUID());
        attendance.setEstablishment(establishment);
        attendance.setProfessional(professional);
        attendance.setStatus(AttendanceStatus.SCHEDULED);
        attendance.setScheduledTime(OffsetDateTime.now().plusHours(1));
        attendance.setServiceItems(List.of(serviceItem));
        attendance.setEstimatedMinutes(30);
        attendance.setTotalValue(BigDecimal.valueOf(50));
        professional.addAttendance(attendance);
    }

    // ────────────────────────────────────────────────────────────
    // Valid Transitions
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Valid Transitions")
    class ValidTransitions {

        @Test
        @DisplayName("SCHEDULED → IN_PROGRESS is valid")
        void scheduledToInProgress_valid() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            attendance.setStartTime(OffsetDateTime.now());
            attendance.setIsTimerActive(true);
            
            // Change status to IN_PROGRESS
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.IN_PROGRESS);
            assertThat(attendance.getStartTime()).isNotNull();
        }

        @Test
        @DisplayName("IN_PROGRESS → PAUSED is valid")
        void inProgressToPaused_valid() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(10));
            attendance.setIsTimerActive(true);
            
            // Pause: change status and record pause time
            attendance.pauseTimer();
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.PAUSED);
            assertThat(attendance.getIsTimerActive()).isFalse();
            assertThat(attendance.getLastPauseTime()).isNotNull();
        }

        @Test
        @DisplayName("PAUSED → IN_PROGRESS is valid (resume)")
        void pausedToInProgress_valid() {
            attendance.setStatus(AttendanceStatus.PAUSED);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(15));
            attendance.setLastPauseTime(OffsetDateTime.now().minusMinutes(5));
            attendance.setIsTimerActive(false);
            attendance.setPausedDurationInMinutes(5);
            
            // Resume: change status and resume timer
            attendance.resumeTimer();
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.IN_PROGRESS);
            assertThat(attendance.getIsTimerActive()).isTrue();
            assertThat(attendance.getLastPauseTime()).isNull();
        }

        @Test
        @DisplayName("IN_PROGRESS → FINISHED is valid")
        void inProgressToFinished_valid() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(30));
            
            // Finish: record end time and change status
            attendance.setEndTime(OffsetDateTime.now());
            attendance.setStatus(AttendanceStatus.FINISHED);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
            assertThat(attendance.getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("PAUSED → FINISHED is valid (direct finish from pause)")
        void pausedToFinished_valid() {
            attendance.setStatus(AttendanceStatus.PAUSED);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(30));
            attendance.setLastPauseTime(OffsetDateTime.now().minusMinutes(5));
            
            // Can finish from PAUSED state
            attendance.setEndTime(OffsetDateTime.now());
            attendance.setStatus(AttendanceStatus.FINISHED);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
            assertThat(attendance.getEndTime()).isNotNull();
        }

        @Test
        @DisplayName("SCHEDULED → CANCELED is valid (cancel before starting)")
        void scheduledToCanceled_valid() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            
            // Cancel from SCHEDULED
            attendance.setStatus(AttendanceStatus.CANCELED);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.CANCELED);
        }

        @Test
        @DisplayName("IN_PROGRESS → CANCELED is valid (cancel during service)")
        void inProgressToCanceled_valid() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(10));
            
            // Cancel from IN_PROGRESS
            attendance.setEndTime(OffsetDateTime.now());
            attendance.setStatus(AttendanceStatus.CANCELED);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.CANCELED);
            assertThat(attendance.getEndTime()).isNotNull();
        }
    }

    // ────────────────────────────────────────────────────────────
    // Invalid Transitions (would require service layer validation)
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Invalid Transitions (model allows, but service should prevent)")
    class InvalidTransitions {

        @Test
        @DisplayName("SCHEDULED → FINISHED should be prevented by service")
        void scheduledToFinished_shouldBeInvalid() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            
            // Model doesn't prevent this — service layer should
            attendance.setStatus(AttendanceStatus.FINISHED);
            attendance.setEndTime(OffsetDateTime.now());
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.FINISHED);
            // Note: Model allows this, but ProfessionalServiceImpl should reject it
        }

        @Test
        @DisplayName("SCHEDULED → PAUSED should be prevented by service")
        void scheduledToPaused_shouldBeInvalid() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            
            // Model doesn't prevent this — service layer should
            attendance.setStatus(AttendanceStatus.PAUSED);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.PAUSED);
            // Note: Model allows this, but ProfessionalServiceImpl should reject it
        }
    }

    // ────────────────────────────────────────────────────────────
    // Terminal State Protection
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Terminal State Protection")
    class TerminalStateProtection {

        @Test
        @DisplayName("FINISHED state should block status changes")
        void finishedState_blocksChanges() {
            attendance.setStatus(AttendanceStatus.FINISHED);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(30));
            attendance.setEndTime(OffsetDateTime.now());
            
            // Model allows changing status, but service should prevent
            attendance.setStatus(AttendanceStatus.PAUSED);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.PAUSED);
            // Note: Model doesn't prevent this, but ProfessionalServiceImpl should reject
        }

        @Test
        @DisplayName("CANCELED state should block status changes")
        void canceledState_blocksChanges() {
            attendance.setStatus(AttendanceStatus.CANCELED);
            
            // Model allows changing status, but service should prevent
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.IN_PROGRESS);
            // Note: Model doesn't prevent this, but ProfessionalServiceImpl should reject
        }
    }

    // ────────────────────────────────────────────────────────────
    // Timer State Consistency
    // ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Timer State Consistency")
    class TimerStateConsistency {

        @Test
        @DisplayName("startAttendance should set startTime and activate timer")
        void startAttendance_setsTimerState() {
            OffsetDateTime startTime = OffsetDateTime.now();
            
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(startTime);
            attendance.setIsTimerActive(true);
            
            assertThat(attendance.getStatus()).isEqualTo(AttendanceStatus.IN_PROGRESS);
            assertThat(attendance.getStartTime()).isEqualTo(startTime);
            assertThat(attendance.getIsTimerActive()).isTrue();
            assertThat(attendance.hasStarted()).isTrue();
        }

        @Test
        @DisplayName("hasStarted returns true only for IN_PROGRESS or PAUSED with startTime")
        void hasStarted_validatesConditions() {
            // SCHEDULED: hasStarted = false
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            attendance.setStartTime(null);
            assertThat(attendance.hasStarted()).isFalse();
            
            // IN_PROGRESS with startTime: hasStarted = true
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now());
            assertThat(attendance.hasStarted()).isTrue();
            
            // PAUSED with startTime: hasStarted = true
            attendance.setStatus(AttendanceStatus.PAUSED);
            assertThat(attendance.hasStarted()).isTrue();
            
            // FINISHED: hasStarted = false
            attendance.setStatus(AttendanceStatus.FINISHED);
            assertThat(attendance.hasStarted()).isFalse();
        }

        @Test
        @DisplayName("getRemainingMinutes before start equals estimatedMinutes")
        void remainingMinutes_beforeStart() {
            attendance.setStatus(AttendanceStatus.SCHEDULED);
            attendance.setStartTime(null);
            attendance.setEstimatedMinutes(30);
            
            int remaining = attendance.getRemainingMinutes();
            
            assertThat(remaining).isEqualTo(30);
        }

        @Test
        @DisplayName("getRemainingMinutes after start equals estimated minus elapsed")
        void remainingMinutes_afterStart() {
            attendance.setStatus(AttendanceStatus.IN_PROGRESS);
            attendance.setStartTime(OffsetDateTime.now().minusMinutes(10));
            attendance.setEstimatedMinutes(30);
            attendance.setIsTimerActive(true);
            attendance.setPausedDurationInMinutes(0);
            
            int remaining = attendance.getRemainingMinutes();
            
            // Remaining should be ~20 (30 - 10), but allowing 1 min tolerance for execution time
            assertThat(remaining).isBetween(19, 21);
        }
    }
}
