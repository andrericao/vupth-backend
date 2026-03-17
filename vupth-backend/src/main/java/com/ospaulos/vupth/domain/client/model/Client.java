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

    public Client(BaseUser baseUser) {
        this.baseUser = baseUser;
        this.id = baseUser.getId();
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
