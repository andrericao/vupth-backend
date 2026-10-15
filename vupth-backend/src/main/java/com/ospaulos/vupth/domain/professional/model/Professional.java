package com.ospaulos.vupth.domain.professional.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.DayHoursColumns;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.establishment.valueObject.WeekdayHours;
import com.ospaulos.vupth.domain.professional.event.WorkStatusChangedEvent;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.AbstractAggregateRoot;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Setter
@Getter
@Entity
@AllArgsConstructor
@Table(name = "professional")
public class Professional extends AbstractAggregateRoot<Professional> {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private BaseUser baseUser;

    @Column(name = "specialty", length = 100, nullable = true)
    private String specialty;

    @Column(name = "is_available", nullable = false)
    private Boolean isAvailable = true;

    /**
     * Status de trabalho (S1): trabalhando / intervalo / indisponivel. Base do {@code open_status}
     * derivado (fatia 2). Nasce {@link ProfessionalStatus#OFF} — um profissional recem-criado nao
     * esta em expediente ate sinalizar. Ver {@link ProfessionalStatus}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "work_status", nullable = false, length = 20)
    private ProfessionalStatus workStatus = ProfessionalStatus.OFF;

    /**
     * "Fechar fila" (S1 fatia 3): {@code false} = fila fechada para NOVOS clientes (walk-in imediato
     * -> 409 {@code PROFESSIONAL_NOT_ACCEPTING_NEW}); quem ja esta segue sendo atendido (drenagem).
     * Intencao para data futura, guest e aceite de corte NAO passam pelo gate. Ao finalizar o ultimo
     * atendimento com a fila fechada, o profissional vira {@code OFF} sozinho (auto-OFF). Reabre
     * automaticamente ao voltar de OFF para expediente (turno novo).
     */
    @Column(name = "accepting_new", nullable = false)
    private Boolean acceptingNew = true;

    /** Fila ou so agenda (D9, por profissional). So troca antes do primeiro cliente na fila do dia. */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_mode", nullable = false, length = 20)
    private com.ospaulos.vupth.domain.professional.valueObject.OperationMode operationMode =
            com.ospaulos.vupth.domain.professional.valueObject.OperationMode.QUEUE;

    /**
     * Auto-fechamento por horario (S1 fatia 5 / BUG1): instante-limite da carencia de 15min apos o
     * {@code closing_time}. Setado pelo scheduler quando o profissional segue em expediente fora do
     * horario (junto com o prompt "quer fechar?" — WS {@code CLOSING_PROMPT} + push); {@code null} =
     * sem carencia pendente. Vencendo sem sinalizacao: sem nada ativo vira {@code OFF} sozinho; com
     * atendimento/fila, a carencia e limpa e o auto-OFF fica com o fim da drenagem (fatia 3).
     * Qualquer {@code PATCH /professional/me/status} (sinalizacao) limpa a carencia.
     */
    @Column(name = "closing_grace_until")
    private OffsetDateTime closingGraceUntil;

    /**
     * Contato (telefone/WhatsApp) que o proprio profissional escolhe mostrar ao cliente na tela dele
     * (pedido de 26/09). Nao e o {@code BaseUser.phone}: aquele e da conta e nunca sai publico.
     * {@code null} = nao expoe.
     */
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    /**
     * Interruptor "expor a posicao na fila" (decisao de 05/09): {@code true} (padrao) o cliente ve a
     * posicao; {@code false} ela some de todos os lugares do app do cliente — o tempo estimado fica
     * sempre. O push de "proximo da fila" obedece a ele (QueuePushService).
     */
    @Column(name = "show_position", nullable = false)
    private Boolean showPosition = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establishment_id")
    @JsonBackReference("establishment-professionals")
    private Establishment establishment;

    @OneToMany(mappedBy = "professional", cascade = CascadeType.ALL)
    private List<Attendance> attendances = new ArrayList<>();

    @OneToMany(mappedBy = "professional", cascade = CascadeType.ALL)
    private List<ProfessionalInterval> intervals = new ArrayList<>();

    @OneToMany(mappedBy = "professional", cascade = CascadeType.ALL)
    private List<ServiceItem> services = new ArrayList<>();

    /**
     * A agenda do proprio profissional (agenda-do-profissional, Andre 05/10): o mesmo modelo do horario da
     * loja (semana + excecoes por data). Nenhuma linha = nao configurada, e vale o horario da loja. So se le
     * e escreve pelo {@link BusinessHours} ({@link #getWorkingHours} / {@link #applyWorkingHours}).
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "professional_weekly_hours", joinColumns = @JoinColumn(name = "professional_id"))
    @MapKeyEnumerated(EnumType.STRING)
    @MapKeyColumn(name = "day_of_week")
    private Map<java.time.DayOfWeek, DayHoursColumns> weeklyHours = new EnumMap<>(java.time.DayOfWeek.class);

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "professional_date_hours", joinColumns = @JoinColumn(name = "professional_id"))
    @MapKeyColumn(name = "hours_date")
    private Map<java.time.LocalDate, DayHoursColumns> dateHours = new HashMap<>();

    /**
     * Agregados denormalizados de avaliacao (item #6). {@code ratingSum} = soma das notas recebidas,
     * {@code ratingCount} = quantidade de votos; media = {@code ratingSum / ratingCount} (ver
     * {@link #getRatingAverage()}). Incrementados a cada {@code AttendanceRating} — evitam rodar
     * {@code AVG()} a cada leitura do card do profissional.
     */
    @Column(name = "rating_sum", nullable = false)
    private long ratingSum = 0L;

    @Column(name = "rating_count", nullable = false)
    private long ratingCount = 0L;

    protected Professional() {};

    public Professional(BaseUser baseUser) {
        this.baseUser = baseUser;
        // O id NAO e atribuido aqui: o @MapsId o deriva do baseUser no persist. Com o id preenchido, o
        // Hibernate 6.6 trata o objeto novo como ja salvo e o merge em cascata falha ("Row was updated or
        // deleted by another transaction"), porque a linha ainda nao existe.
    }

    /** Antes do flush o campo ainda esta nulo; o id e sempre o do usuario (MapsId). */
    public UUID getId() {
        return id != null ? id : (baseUser != null ? baseUser.getId() : null);
    }

    /**
     * Troca o status de trabalho e, se mudou, registra o {@link WorkStatusChangedEvent} — publicado no
     * {@code save} do repositorio. E dele que sai o expediente real dos relatorios (Partner S4).
     */
    public void setWorkStatus(ProfessionalStatus workStatus) {
        ProfessionalStatus from = this.workStatus;
        this.workStatus = workStatus;
        if (from != workStatus) {
            registerEvent(new WorkStatusChangedEvent(getId(),
                    establishment != null ? establishment.getId() : null,
                    from, workStatus, OffsetDateTime.now()));
        }
    }

    public String getName(){
        return this.baseUser.getFirstName();
    }

    /** Registra uma nota (item #6): soma no total e incrementa a contagem de votos. */
    public void addRating(int score) {
        this.ratingSum += score;
        this.ratingCount++;
    }

    /** Media de avaliacao (2 casas) ou {@code null} quando ainda nao ha votos. */
    @Transient
    public Double getRatingAverage() {
        if (ratingCount == 0) {
            return null;
        }
        return Math.round((double) ratingSum / ratingCount * 100.0) / 100.0;
    }

    public void addAttendance(Attendance attendance) {
        attendances.add(attendance);
        attendance.setProfessional(this);
    }

    public void removeAttendance(Attendance attendance) {
        attendances.remove(attendance);
        attendance.setProfessional(null);
    }

    public List<Attendance> getScheduledAttendances() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.SCHEDULED)
                .sorted(Comparator.comparing(Attendance::getScheduledTime))
                .collect(Collectors.toList());
    }

    /**
     * Fila de espera completa (item #19): SCHEDULED + LATE, ordenada por scheduledTime.
     * O cliente marcado como LATE (atrasado) NAO sai da fila — apenas cai de posicao — entao
     * precisa participar da renormalizacao de horarios junto com os SCHEDULED, mantendo sua
     * posicao relativa (senao o horario antigo congelado o jogaria pro fim da fila).
     */
    public List<Attendance> getWaitingAttendances() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.SCHEDULED
                        || a.getStatus() == AttendanceStatus.LATE)
                .sorted(Comparator.comparing(Attendance::getScheduledTime))
                .collect(Collectors.toList());
    }

    /**
     * Atendimento "atual" do profissional: o IN_PROGRESS ou, na ausencia dele, o PAUSED
     * (Partner BUG7). Um atendimento pausado continua sendo o atual — exposto e retomavel —
     * ate resume/finish; sem esse fallback ele sumia de todos os read-models (orfao) e o
     * profissional nao conseguia retomar. Tambem serve de guarda ("ja tem atendimento ativo"):
     * nao se inicia um novo enquanto ha um pausado.
     */
    public Optional<Attendance> getCurrentAttendance() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.IN_PROGRESS)
                .findFirst()
                .or(() -> attendances.stream()
                        .filter(a -> a.getStatus() == AttendanceStatus.PAUSED)
                        .findFirst());
    }

    // Or more generic if other statuses are needed
    public Optional<Attendance> getAttendanceByStatus(AttendanceStatus status) {
        return attendances.stream()
                .filter(a -> a.getStatus() == status)
                .findFirst();
    }

    public BigDecimal getTotalRevenue() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.FINISHED)
                .map(Attendance::getTotalValue)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /*
    public Boolean getIsAvailable(){
        return isAvailable;
    }
    */

    /*
    TODO:
     calcular tempo de fila para agendados
     ATTENDANCE TEM APENAS LISTA DE ATENDIMENTOS AGENDADOS PRECISAMOS DO IN_PROGRESS
     */

    public BusinessHours getWorkingHours() {
        List<WeekdayHours> week = weeklyHours.entrySet().stream()
                .map(e -> new WeekdayHours(e.getKey(), e.getValue().toDayHours()))
                .toList();
        Map<java.time.LocalDate, DayHours> exceptions = new HashMap<>();
        dateHours.forEach((date, columns) -> exceptions.put(date, columns.toDayHours()));
        return BusinessHours.of(week, exceptions);
    }

    public void applyWorkingHours(BusinessHours hours) {
        weeklyHours.clear();
        hours.week().forEach(w -> weeklyHours.put(w.day(), DayHoursColumns.from(w.hours())));
        dateHours.clear();
        hours.allExceptions().forEach((date, h) -> dateHours.put(date, DayHoursColumns.from(h)));
    }

    /** O horario que vale de fato: a intersecao do da loja com o dele (agenda-do-profissional). */
    public BusinessHours getEffectiveHours() {
        return BusinessHours.effective(
                establishment != null ? establishment.getBusinessHours() : BusinessHours.notConfigured(),
                getWorkingHours());
    }
}
