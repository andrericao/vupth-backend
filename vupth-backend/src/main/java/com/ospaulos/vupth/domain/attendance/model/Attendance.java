package com.ospaulos.vupth.domain.attendance.model;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.professional.model.Professional;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "ATTENDANCE")
public class Attendance implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Column(name = "guest_name")
    private String guestName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id", nullable = false)
    private Professional professional;

    @ManyToMany
    @JoinTable(
            name = "attendance_service_item",
            joinColumns = @JoinColumn(name = "attendance_id"),
            inverseJoinColumns = @JoinColumn(name = "service_item_id")
    )
    private List<ServiceItem> serviceItems = new ArrayList<>();

    @Column(name = "duration_minutes")
    private Integer durationInMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AttendanceStatus status;

    @Column(name = "scheduled_time", nullable = false)
    private OffsetDateTime scheduledTime;

    @Column(name = "estimated_minutes", nullable = false)
    private Integer estimatedMinutes;

    /**
     * Refeicoes (recorrentes) que estavam no ETA do cliente NO INGRESSO (item #37 — lock).
     * Travadas aqui para nao sairem do ETA dele por ritmo da fila; cada uma soma sua
     * {@code durationMinutes} (exceto se pulada no dia, #41 / removida). Ver
     * {@code QueueUtils.lockedClientWait}. So usada no ETA individual do cliente.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "attendance_locked_intervals",
            joinColumns = @JoinColumn(name = "attendance_id"))
    @Column(name = "interval_id", nullable = false)
    private Set<UUID> lockedIntervalIds = new HashSet<>();

    @Column(name = "total_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalValue;

    /**
     * Encaixe (item #38): marca este atendimento como "encaixado" no meio da fila. Enquanto
     * espera (SCHEDULED), ele NAO contribui o tempo cheio (D) para o ETA dos clientes de tras —
     * contribui um valor RAMPADO que sobe de 0 ate D conforme a fila anda, para que o tempo do
     * encaixe seja absorvido sem solavanco. Ver {@code QueueUtils.effectiveEstimatedMinutes}.
     */
    @Column(name = "is_fit", nullable = false)
    private boolean fit = false;

    /**
     * Encaixe (item #38): numero de clientes que estavam a frente NO INSTANTE do encaixe
     * (o "length inicial"). E o divisor do ramp: cada cliente que sai da frente libera uma
     * fatia de {@code D / fitAheadLength}. Null quando {@code fit == false}.
     */
    @Column(name = "fit_ahead_length")
    private Integer fitAheadLength;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "start_time")
    private OffsetDateTime startTime;

    @Column(name = "end_time")
    private OffsetDateTime endTime;

    @Column(name = "paused_duration_minutes")
    private Integer pausedDurationInMinutes = 0;

    @Column(name = "is_timer_active")
    private Boolean isTimerActive = false;

    @Column(name = "last_pause_time")
    private OffsetDateTime lastPauseTime;

    @PrePersist
    @PreUpdate
    protected void calculateFields() {
        if (this.scheduledTime == null) {
            this.scheduledTime = OffsetDateTime.now();
        }
        calculateEstimatedMinutes();
        calculateTotalValue();

        if (this.serviceItems != null && !this.serviceItems.isEmpty() &&
                (this.totalValue == null || this.totalValue.compareTo(BigDecimal.ZERO) == 0)) {
            throw new IllegalStateException("Total attendance value cannot be zero when service items are associated.");
        }
    }

    protected void calculateEstimatedMinutes() {
        if (this.estimatedMinutes == null) {
            this.estimatedMinutes = this.serviceItems == null ? 0 :
                    this.serviceItems.stream()
                            .filter(Objects::nonNull)
                            .mapToInt(item -> item.getEstimatedMinutes() != null ?
                                    item.getEstimatedMinutes() : 0)
                            .sum();
        }
    }

    protected void calculateTotalValue() {
        if (this.serviceItems != null && !this.serviceItems.isEmpty()) {
            this.totalValue = this.serviceItems.stream()
                    .filter(Objects::nonNull)
                    .map(ServiceItem::getPrice)
                    .filter(price -> price != null && price.compareTo(BigDecimal.ZERO) > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (this.totalValue.compareTo(BigDecimal.ZERO) == 0) {
                throw new IllegalStateException("No service items with a valid price were found.");
            }

        } else {
            this.totalValue = BigDecimal.ZERO;
        }
    }

    @Transient
    public int getEstimatedTotalMinutes() {
        return this.estimatedMinutes != null ? this.estimatedMinutes : 0;
    }

    @Transient
    public BigDecimal getTotalValue() {
        return this.totalValue != null ? this.totalValue : BigDecimal.ZERO;
    }

    @Transient
    public int getServiceDurationInMinutes() {
        return getServiceDurationInMinutes(OffsetDateTime.now());
    }

    @Transient
    public int getServiceDurationInMinutes(OffsetDateTime referenceTime) {
        if (startTime == null) return 0;
        OffsetDateTime end = (endTime != null) ? endTime : referenceTime;
        long totalMinutes = Duration.between(startTime, end).toMinutes();
        int pausedMinutes = pausedDurationInMinutes != null ? pausedDurationInMinutes : 0;
        return (int) Math.max(totalMinutes - pausedMinutes, 0);
    }

    public void pauseTimer() {
        if (startTime != null && Boolean.TRUE.equals(isTimerActive) && getStatus() == AttendanceStatus.IN_PROGRESS) {
            lastPauseTime = OffsetDateTime.now();
            isTimerActive = false;
            status = AttendanceStatus.PAUSED;
        }
    }

    public void resumeTimer() {
        if (status == AttendanceStatus.PAUSED && Boolean.FALSE.equals(isTimerActive) && lastPauseTime != null) {
            int pauseMinutes = (int) Duration.between(lastPauseTime, OffsetDateTime.now()).toMinutes();
            pausedDurationInMinutes = (pausedDurationInMinutes != null ? pausedDurationInMinutes : 0) + Math.max(pauseMinutes, 0);
            lastPauseTime = null;
            isTimerActive = true;

            status = AttendanceStatus.IN_PROGRESS;
        }
    }

    @Transient
    public int getRemainingMinutes() {
        return getRemainingMinutes(OffsetDateTime.now());
    }

    /**
     * Parameterized version for remaining time calculations
     */
    @Transient
    public int getRemainingMinutes(OffsetDateTime referenceTime) {
        if (!hasStarted()) {
            return getEstimatedTotalMinutes();
        }

        int serviceMinutes = getServiceDurationInMinutes(referenceTime);
        int estimated = getEstimatedTotalMinutes(); // With intervals / pause

        return Math.max(0, estimated - serviceMinutes);
    }

    public int calculatePausedMinutes(Attendance attendance, OffsetDateTime referenceTime) {
        int accumulatedPaused = attendance.getPausedDurationInMinutes() != null ?
                attendance.getPausedDurationInMinutes() : 0;

        if (attendance.getLastPauseTime() != null) {
            int currentPauseMinutes = (int) Duration.between(attendance.getLastPauseTime(), referenceTime).toMinutes();
            accumulatedPaused += Math.max(currentPauseMinutes, 0);
        }

        return accumulatedPaused;
    }

    @Transient
    public boolean hasStarted() {
        return startTime != null && (status == AttendanceStatus.IN_PROGRESS || status == AttendanceStatus.PAUSED);
    }
}