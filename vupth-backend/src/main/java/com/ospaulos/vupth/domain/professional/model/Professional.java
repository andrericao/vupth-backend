package com.ospaulos.vupth.domain.professional.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
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

    protected Professional() {};

    public Professional(BaseUser baseUser) {
        this.baseUser = baseUser;
        this.id = baseUser.getId();
    }

    public String getName(){
        return this.baseUser.getFirstName();
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

    public Optional<Attendance> getCurrentAttendance() {
        return attendances.stream()
                .filter(a -> a.getStatus() == AttendanceStatus.IN_PROGRESS)
                .findFirst();
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
