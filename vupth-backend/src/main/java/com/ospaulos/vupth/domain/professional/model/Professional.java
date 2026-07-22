package com.ospaulos.vupth.domain.professional.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Setter
@Getter
@Entity
@AllArgsConstructor
@Table(name = "professional")
public class Professional {

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
        this.id = baseUser.getId();
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
}
