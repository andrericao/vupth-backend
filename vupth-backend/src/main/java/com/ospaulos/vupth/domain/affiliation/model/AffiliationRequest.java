package com.ospaulos.vupth.domain.affiliation.model;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.professional.model.Professional;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name = "affiliation_request",
       uniqueConstraints = @UniqueConstraint(
               name = "uk_affiliation_pending",
               columnNames = {"professional_id", "establishment_id", "status"}))
public class AffiliationRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professional_id", nullable = false)
    private Professional professional;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "establishment_id", nullable = false)
    private Establishment establishment;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private AffiliationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private AffiliationStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    public AffiliationRequest(Professional professional, Establishment establishment, AffiliationType type) {
        this.professional = professional;
        this.establishment = establishment;
        this.type = type;
        this.status = AffiliationStatus.PENDING;
    }

    public void accept() {
        this.status = AffiliationStatus.ACCEPTED;
        this.resolvedAt = LocalDateTime.now();
    }

    public void reject() {
        this.status = AffiliationStatus.REJECTED;
        this.resolvedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = AffiliationStatus.CANCELLED;
        this.resolvedAt = LocalDateTime.now();
    }
}
