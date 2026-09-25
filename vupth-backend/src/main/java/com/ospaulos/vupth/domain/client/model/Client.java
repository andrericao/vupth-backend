package com.ospaulos.vupth.domain.client.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.*;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "client", indexes = {
        @Index(name = "idx_client_total_spent", columnList = "total_spent DESC"),
        @Index(name = "idx_client_loyalty_points", columnList = "loyalty_points DESC"),
        @Index(name = "idx_client_since", columnList = "client_since")
})
public class Client {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private BaseUser baseUser;

    // todo: add CASCADE on Delete
    @OneToMany(mappedBy = "client", fetch = FetchType.LAZY)
    private List<Attendance> attendances = new ArrayList<>();

    /**
     * Contador denormalizado de strikes/penalidades (item #39, Fase 1). Incrementado a cada
     * {@link com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent} (ex.: cancelamento tardio
     * com ETA &lt; 1h). Fase 1 apenas acumula e expoe — sem bloqueio automatico.
     */
    @Column(name = "strike_count", nullable = false)
    private int strikeCount = 0;

    /**
     * Atrasos acumulados (D4, Andre 25/09): soma entre atendimentos e zera quando a multa nasce — o 3o
     * atraso custa R$ 5,00. Exposto com {@link #getLatesUntilPenalty()} para o aviso "este e o seu 2o".
     */
    @Column(name = "late_count", nullable = false)
    private int lateCount = 0;

    /** Atrasos que faltam para a multa (3, 2 ou 1). */
    public int getLatesUntilPenalty() {
        return com.ospaulos.vupth.domain.client.service.LateArrivalService.LATES_PER_PENALTY - this.lateCount;
    }

    public Client(BaseUser baseUser) {
        this.baseUser = baseUser;
        this.id = baseUser.getId();
    }

    public void incrementStrike() {
        this.strikeCount++;
    }

    public void decrementStrike() {
        this.strikeCount = Math.max(0, this.strikeCount - 1);
    }

    public String getFirstName(){
        return this.baseUser.getFirstName();
    }

    public String getFullName(){
        return this.baseUser.getFirstName() + " " + this.baseUser.getLastName();
    }

    public String getEmail(){
        return this.baseUser.getEmail();
    }


/*
    @JsonIgnore
    public List<Attendance> getAttendanceHistory() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.FINISHED)
                .sorted(Comparator.comparing(Attendance::getEndTime).reversed())
                .collect(Collectors.toList());
    }

    @JsonIgnore
    public Optional<Attendance> getNextScheduledAttendance() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.SCHEDULED)
                .filter(a -> a.getScheduledTime().isAfter(OffsetDateTime.now()))
                .min(Comparator.comparing(Attendance::getScheduledTime));
    }*/
}
