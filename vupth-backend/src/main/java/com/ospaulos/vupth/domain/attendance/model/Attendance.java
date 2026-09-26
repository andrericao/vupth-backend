package com.ospaulos.vupth.domain.attendance.model;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import com.ospaulos.vupth.domain.professional.model.Professional;
import jakarta.persistence.*;
import lombok.AccessLevel;
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
     * Divisao do preco CONGELADA na criacao (Receita LIQUIDA): a soma, servico a servico, do que o
     * profissional recebe, dos 4% da empresa de pagamento e da parte do app. As tres somam o
     * {@code totalValue}. Nulas quando algum servico e legado (sem recebe) — meia soma seria um
     * numero com cara de dado. So o {@link #onCreate} escreve aqui.
     */
    @Setter(AccessLevel.NONE)
    @Column(name = "pro_receives", precision = 10, scale = 2)
    private BigDecimal proReceives;

    @Setter(AccessLevel.NONE)
    @Column(name = "intermediary_fee", precision = 10, scale = 2)
    private BigDecimal intermediaryFee;

    @Setter(AccessLevel.NONE)
    @Column(name = "platform_fee", precision = 10, scale = 2)
    private BigDecimal platformFee;

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

    /**
     * Intencao de fila com horario-alvo (item #40, Opcao B): marca este atendimento como
     * INTENCAO (ainda nao confirmado). Enquanto {@code isIntent}, ele ordena pelo {@code targetTime}
     * e CEDE a vez a quem chega depois — desde que o atendimento previsto da posicao dele ainda
     * seja ANTES do alvo. Quando o previsto encosta/cruza o alvo, vira confirmado
     * ({@code isIntent=false}) automaticamente. O status permanece {@code SCHEDULED}.
     * Ver {@code QueueUtils.isTargetFeasible}/{@code shouldConfirmIntent}/{@code assignAgendaSlot}.
     */
    @Column(name = "is_intent", nullable = false)
    private boolean intent = false;

    /**
     * Intencao de fila (item #40): horario-alvo escolhido pelo cliente (ex.: 17:00). Null quando
     * {@code intent == false}. A ordenacao da fila de espera usa este horario para a intencao
     * (em vez do {@code scheduledTime}), e a confirmacao dispara quando o previsto o alcanca.
     */
    @Column(name = "target_time")
    private OffsetDateTime targetTime;

    /**
     * Intencao de atendimento (backlog #4 / status {@link AttendanceStatus#INTENTION}): horario
     * PRETENDIDO pelo cliente (ex.: 17:00 de amanha). Preenchido apenas quando o atendimento nasce
     * como INTENTION (fora da fila). Diferente do {@code targetTime} do #40 (que vive DENTRO da fila
     * como flag {@code is_intent} sobre um SCHEDULED): a INTENTION so entra na fila ao ser promovida.
     * Null nos atendimentos normais.
     */
    @Column(name = "desired_time")
    private OffsetDateTime desiredTime;

    /**
     * Ate quando o cliente sai da fila de graca (D4, tolerancia de 24/09). Gravado no ingresso, pela
     * espera prevista naquele momento ({@code ExitPenaltyRule}); nao se recalcula depois — a janela
     * prometida na entrada e a que vale. Null nos atendimentos anteriores a regra.
     */
    @Column(name = "tolerance_until")
    private OffsetDateTime toleranceUntil;

    /**
     * Horario de atendimento prometido na entrada (entrada + espera comunicada) — D4. O profissional so
     * marca o cliente atrasado 5 min depois dele (e da chegada da vez), para ninguem ser punido por nao
     * estar la antes do que lhe foi dito. Null nos atendimentos anteriores a regra.
     */
    @Column(name = "promised_time")
    private OffsetDateTime promisedTime;

    /**
     * Quantas marcacoes de atraso deste atendimento entraram no contador do cliente (D4) — as feitas
     * depois da tolerancia. O undo-late desconta exatamente estas.
     */
    @Column(name = "counted_lates", nullable = false)
    private int countedLates = 0;

    /**
     * Notificacoes push (backlog #1): true depois que este atendimento ja recebeu o push
     * "voce e o proximo" ao alcancar a frente da fila. Evita reenviar a cada nova mutacao
     * enquanto continua na frente. Ver {@code QueuePushService.notifyNextInLine}.
     */
    @Column(name = "next_notified", nullable = false)
    private boolean nextNotified = false;

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

    /**
     * Na criacao o preco e CONGELADO: total e divisao saem dos servicos deste momento. Preco de
     * servico muda; o que foi cobrado naquele dia nao pode mudar junto.
     */
    @PrePersist
    public void onCreate() {
        if (this.scheduledTime == null) {
            this.scheduledTime = OffsetDateTime.now();
        }
        calculateEstimatedMinutes();
        calculateTotalValue();
        freezePriceSplit();
        assertTotalValue();
    }

    /**
     * Na atualizacao NADA de preco e recalculado — antes o total era refeito a cada escrita a partir
     * do preco ATUAL dos servicos, e um start depois de uma troca de preco reescrevia a historia.
     * So um atendimento sem total (linha antiga) recebe o calculo.
     */
    @PreUpdate
    public void onUpdate() {
        if (this.scheduledTime == null) {
            this.scheduledTime = OffsetDateTime.now();
        }
        calculateEstimatedMinutes();
        if (this.totalValue == null) {
            calculateTotalValue();
        }
        assertTotalValue();
    }

    private void assertTotalValue() {
        if (this.serviceItems != null && !this.serviceItems.isEmpty() &&
                (this.totalValue == null || this.totalValue.compareTo(BigDecimal.ZERO) == 0)) {
            throw new IllegalStateException("Total attendance value cannot be zero when service items are associated.");
        }
    }

    private void freezePriceSplit() {
        this.proReceives = null;
        this.intermediaryFee = null;
        this.platformFee = null;
        if (this.serviceItems == null || this.serviceItems.isEmpty()) {
            return;
        }
        BigDecimal pro = BigDecimal.ZERO;
        BigDecimal intermediary = BigDecimal.ZERO;
        BigDecimal platform = BigDecimal.ZERO;
        for (ServiceItem item : this.serviceItems) {
            PriceSplit split = item == null ? null : item.getPriceSplit();
            if (split == null) {
                return;
            }
            pro = pro.add(split.proReceives());
            intermediary = intermediary.add(split.intermediaryFee());
            platform = platform.add(split.platformFee());
        }
        this.proReceives = pro;
        this.intermediaryFee = intermediary;
        this.platformFee = platform;
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

    /**
     * Tempo de SERVICO ate {@code referenceTime}: do inicio ao fim, menos as pausas encerradas.
     * Com uma pausa em curso o servico para no instante da pausa ({@code lastPauseTime}) — o
     * tempo parado nao e servico, e o que falta nao pode diminuir enquanto ninguem e atendido.
     */
    @Transient
    public int getServiceDurationInMinutes(OffsetDateTime referenceTime) {
        if (startTime == null) return 0;
        OffsetDateTime end = endTime != null ? endTime
                : lastPauseTime != null ? lastPauseTime
                : referenceTime;
        long totalMinutes = Duration.between(startTime, end).toMinutes();
        return (int) Math.max(totalMinutes - accumulatedPausedMinutes(), 0);
    }

    /** Pausas encerradas mais a pausa em curso, ate {@code referenceTime}. */
    @Transient
    public int getPausedMinutes(OffsetDateTime referenceTime) {
        int openPause = lastPauseTime != null
                ? (int) Math.max(Duration.between(lastPauseTime, referenceTime).toMinutes(), 0)
                : 0;
        return accumulatedPausedMinutes() + openPause;
    }

    private int accumulatedPausedMinutes() {
        return pausedDurationInMinutes != null ? pausedDurationInMinutes : 0;
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

    @Transient
    public boolean hasStarted() {
        return startTime != null && (status == AttendanceStatus.IN_PROGRESS || status == AttendanceStatus.PAUSED);
    }
}