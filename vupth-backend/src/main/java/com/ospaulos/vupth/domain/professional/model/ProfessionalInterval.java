package com.ospaulos.vupth.domain.professional.model;

import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "professional_intervals", indexes = {
        @Index(name = "idx_professional_date", columnList = "professional_id, start_time"),
        @Index(name = "idx_professional_status", columnList = "professional_id, status"),
        @Index(name = "idx_professional_type", columnList = "professional_id, interval_type")
})
public class ProfessionalInterval {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id", nullable = false)
    private Professional professional;

    @Column(nullable = false)
    private Integer durationMinutes;

    @Column
    private OffsetDateTime startTime;

    @Column
    private OffsetTime fixedStartTime;

    @Column
    private String recurrencePattern;

    @Column
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "interval_type", nullable = false)
    private IntervalType intervalType;

    @Column
    private OffsetDateTime actualStartTime;

    @Column
    private OffsetDateTime actualEndTime;

    @Column
    private OffsetDateTime plannedEndTime;

    @Column(name = "extended_minutes", columnDefinition = "INTEGER DEFAULT 0")
    private Integer extendedMinutes = 0;

    @Column(name = "max_extension_minutes", columnDefinition = "INTEGER DEFAULT 30")
    private Integer maxExtensionMinutes = 30;

    @Column(name = "is_active", columnDefinition = "BOOLEAN DEFAULT false")
    private Boolean isActive = false;

    @Column(name = "finished_early", columnDefinition = "BOOLEAN DEFAULT false")
    private Boolean finishedEarly = false;

    @Column(name = "auto_finish_reason")
    private String autoFinishReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void startInterval() {
        if (this.isActive) {
            throw new IllegalStateException("Interval is already is_active");
        }

        OffsetDateTime now = OffsetDateTime.now();
        this.actualStartTime = now;

        if (this.intervalType == IntervalType.RECURRENT && this.fixedStartTime != null) {
            OffsetDateTime scheduledToday = now.toLocalDate().atTime(this.fixedStartTime);
            if (now.isBefore(scheduledToday.plusMinutes(5))) {
                this.actualStartTime = scheduledToday;
            }
        }

        this.plannedEndTime = this.actualStartTime.plusMinutes(this.durationMinutes);
        this.isActive = true;
        this.finishedEarly = false;
        this.actualEndTime = null;
    }

    public void finishInterval(String reason) {
        if (!this.isActive) {
            throw new IllegalStateException("Interval is not is_active");
        }

        this.actualEndTime = OffsetDateTime.now();
        this.isActive = false;
        this.finishedEarly = this.actualEndTime.isBefore(this.plannedEndTime);
        this.autoFinishReason = reason;
    }

    public void extendInterval() {
        extendInterval(5);
    }

    public void extendInterval(int additionalMinutes) {
        if (!this.isActive) {
            throw new IllegalStateException("Cannot extend inactive interval");
        }

        if (this.extendedMinutes + additionalMinutes > this.maxExtensionMinutes) {
            throw new IllegalStateException(
                    String.format("Cannot extend more than %d minutes total", this.maxExtensionMinutes)
            );
        }

        this.extendedMinutes += additionalMinutes;
        this.plannedEndTime = this.plannedEndTime.plusMinutes(additionalMinutes);
    }

    public long getRemainingMinutes() {
        if (!this.isActive || this.plannedEndTime == null) {
            return 0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        if (now.isAfter(this.plannedEndTime)) {
            return 0;
        }

        return java.time.Duration.between(now, this.plannedEndTime).toMinutes();
    }

    public boolean isOverdue() {
        return this.isActive && this.plannedEndTime != null &&
                OffsetDateTime.now().isAfter(this.plannedEndTime);
    }

    public int getTotalPlannedDuration() {
        return this.durationMinutes + (this.extendedMinutes != null ? this.extendedMinutes : 0);
    }

    public Long getActualDurationMinutes() {
        if (this.actualStartTime == null) {
            return null;
        }

        OffsetDateTime endTime = this.actualEndTime != null ?
                this.actualEndTime : OffsetDateTime.now();

        return java.time.Duration.between(this.actualStartTime, endTime).toMinutes();
    }

    public boolean canExtend(int minutes) {
        return this.isActive &&
                (this.extendedMinutes + minutes) <= this.maxExtensionMinutes;
    }

    public static ProfessionalInterval createLunchInterval(Professional professional) {
        return createRecurring(
                professional,
                60,
                OffsetTime.of(12, 0, 0, 0, ZoneOffset.UTC),
                "WEEKDAYS",
                "Lunch Break"
        );
    }

    public static ProfessionalInterval createDinnerInterval(Professional professional) {
        return createRecurring(
                professional,
                60, // padronizado em 1h (spec item #11, regra 1)
                OffsetTime.of(18, 0, 0, 0, ZoneOffset.UTC),
                "WEEKDAYS",
                "Dinner Break"
        );
    }

    public static ProfessionalInterval createRecurring(
            Professional professional,
            Integer durationMinutes,
            OffsetTime fixedStartTime,
            String recurrencePattern,
            String description) {

        ProfessionalInterval interval = new ProfessionalInterval();
        interval.professional = professional;
        interval.durationMinutes = durationMinutes;
        interval.fixedStartTime = fixedStartTime;
        interval.recurrencePattern = recurrencePattern;
        interval.description = description;
        interval.intervalType = IntervalType.RECURRENT;
        interval.extendedMinutes = 0;
        interval.isActive = false;

        return interval;
    }

    // Claude
    public static ProfessionalInterval createUnexpected(
            Professional professional,
            Integer durationMinutes,
            OffsetDateTime startTime,
            String description) {

        ProfessionalInterval interval = new ProfessionalInterval();
        interval.professional = professional;
        interval.durationMinutes = durationMinutes;
        interval.startTime = startTime;
        interval.description = description;
        interval.intervalType = IntervalType.UNEXPECTED;
        interval.extendedMinutes = 0;
        interval.isActive = true; // intervalos agendados ficam ativos para aparecer na listagem

        return interval;
    }

    /*

    public static ProfessionalInterval createUnexpected(
            Professional professional,
            Integer durationMinutes,
            OffsetDateTime startTime,
            String description) {

        ProfessionalInterval interval = new ProfessionalInterval();
        interval.professional = professional;
        interval.durationMinutes = durationMinutes;
        interval.startTime = startTime;
        interval.description = description;
        interval.intervalType = IntervalType.UNEXPECTED;
        interval.extendedMinutes = 0;

        OffsetDateTime now = OffsetDateTime.now();
        if (startTime == null || startTime.isBefore(now.plusMinutes(1))) {
            interval.startInterval();
        }

        return interval;
    }*/
}
