package com.ospaulos.vupth.domain.professional.model;

import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
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

    // BUG2b: LocalTime (relogio de parede no fuso da loja), NAO OffsetTime — a coluna e TIME (sem
    // timezone) e o Hibernate normalizava o offset para UTC (12:00-03:00 gravava 15:00). A projecao
    // do horario no dia usa BusinessClock.at(dia, fixedStartTime).
    @Column
    private LocalTime fixedStartTime;

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

    /**
     * Dias em que esta refeicao recorrente foi PULADA ("Nao almocar" do dia — item #41).
     * O skip e por (intervalo, data): a recorrencia continua intacta para os outros dias.
     * Consumido por {@link #isSkippedOn} para derrubar a refeicao do ETA so naquele dia.
     */
    // @BatchSize (B10): isSkippedOn() e chamado para CADA refeicao recorrente na estimativa, e a
    // listagem publica estima a fila de todas as lojas da pagina. Sem o lote, cada refeicao
    // custava uma consulta so para responder "foi pulada hoje?".
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 200)
    @CollectionTable(name = "professional_interval_skips",
            joinColumns = @JoinColumn(name = "interval_id"))
    @Column(name = "skip_date", nullable = false)
    private Set<LocalDate> skippedDates = new HashSet<>();

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

    /**
     * Estado de execucao day-scoped (spec item #11d): so e considerado "iniciado"
     * se o inicio real foi na data informada. Estado de outro dia NAO carrega
     * (evita carryover e "duplo-almoco"); o registro recorrente volta amanha como previsto.
     */
    public boolean isStartedOn(java.time.LocalDate date) {
        return Boolean.TRUE.equals(this.isActive)
                && this.actualStartTime != null
                && BusinessClock.dateAt(this.actualStartTime).equals(date);
    }

    /**
     * True se o intervalo ja foi ENCERRADO na data informada (refeicao ja acontecida
     * hoje). Usado para nao reprojetar como "previsto" uma refeicao ja consumida no dia.
     */
    public boolean isDoneOn(java.time.LocalDate date) {
        return this.actualEndTime != null
                && BusinessClock.dateAt(this.actualEndTime).equals(date);
    }

    /** True se a refeicao foi marcada como "pular" (Nao almocar) na data informada (item #41). */
    public boolean isSkippedOn(LocalDate date) {
        return this.skippedDates != null && this.skippedDates.contains(date);
    }

    /** Marca a refeicao para pular na data (idempotente). */
    public void skipOn(LocalDate date) {
        if (this.skippedDates == null) {
            this.skippedDates = new HashSet<>();
        }
        this.skippedDates.add(date);
    }

    /** Desfaz o skip da data — a refeicao volta a contar no ETA (idempotente). */
    public void unskipOn(LocalDate date) {
        if (this.skippedDates != null) {
            this.skippedDates.remove(date);
        }
    }

    public void startInterval() {
        if (this.isActive) {
            throw new IllegalStateException("Interval is already is_active");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        this.actualStartTime = now;

        if (this.intervalType == IntervalType.RECURRENT && this.fixedStartTime != null) {
            // BUG2b: o horario fixo e relogio de parede — projeta no dia da LOJA, nao no dia UTC.
            OffsetDateTime scheduledToday =
                    BusinessClock.at(BusinessClock.dateAt(now), this.fixedStartTime);
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
        this.finishedEarly = this.plannedEndTime != null && this.actualEndTime.isBefore(this.plannedEndTime);
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
                LocalTime.of(12, 0),
                "WEEKDAYS",
                "Lunch Break"
        );
    }

    public static ProfessionalInterval createDinnerInterval(Professional professional) {
        return createRecurring(
                professional,
                60, // padronizado em 1h (spec item #11, regra 1)
                LocalTime.of(18, 0),
                "WEEKDAYS",
                "Dinner Break"
        );
    }

    public static ProfessionalInterval createRecurring(
            Professional professional,
            Integer durationMinutes,
            LocalTime fixedStartTime,
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
