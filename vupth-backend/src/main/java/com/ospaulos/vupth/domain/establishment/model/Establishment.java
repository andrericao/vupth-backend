package com.ospaulos.vupth.domain.establishment.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.domain.owner.model.Owner;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.user.model.UserAddress;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@Entity
@AllArgsConstructor
@NoArgsConstructor
@Table(name="ESTABLISHMENT")
public class Establishment implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "establishment_services",
            joinColumns = @JoinColumn(name = "establishment_id"))
    @Column(name = "service", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<ServiceType> services;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String urlImage;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "instagram", length = 100)
    private String instagram;

    // Horario de funcionamento (opcional, editavel). Nullable: estabelecimentos
    // antigos / recem-criados podem nao ter ainda. Usado no gate de abertura e na
    // validacao de que as refeicoes cabem na janela de funcionamento (#11g2).
    @Column(name = "opening_time")
    private OffsetTime openingTime;

    @Column(name = "closing_time")
    private OffsetTime closingTime;

    @CreationTimestamp
    @JsonProperty("created_at")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type")
    private ServiceType serviceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EstablishmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_open_status", nullable = false)
    private OpenStatus openStatus;

    @Column(name = "last_status_change")
    private ZonedDateTime lastStatusChange;

    @JsonManagedReference("establishment-professionals")
    @OneToMany(mappedBy = "establishment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Professional> professionals = new ArrayList<>();

    @JsonManagedReference
    @OneToMany(mappedBy = "establishment", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<EstablishmentAddress> addresses = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference
    @JoinColumn(name = "owner_id",
        foreignKey = @ForeignKey(name = "fk_establishment_owner"))
    private Owner owner;

    public boolean addProfessional(Professional professional) {
            return professionals.add(professional);
    }

    public boolean removeEmployee(Professional professional) {
        return professionals.remove(professional);
    }

    public boolean hasProfessional(Professional professional) {
        return professionals.contains(professional);
    }

    public Set<Professional> getAllProfessionals() {
        return new HashSet<>(professionals);
    }

    public List<ServiceItem> getServicesItemByEstablishment(){
        return professionals.stream()
                .flatMap(p -> p.getServices().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    public Set<Professional> getAvailableProfessionals() {
        return professionals.stream()
                .filter(Professional::getIsAvailable)
                .collect(Collectors.toSet());
    }

    public Set<Professional> getProfessionalsBySpecialty(String specialty) {
        return professionals.stream()
                .filter(p -> p.getSpecialty().equalsIgnoreCase(specialty))
                .collect(Collectors.toSet());
    }

    public void addAddress(EstablishmentAddress address) {
        addresses.add(address);
        address.setEstablishment(this);
    }

    public void removeAddress(EstablishmentAddress address) {
        addresses.remove(address);
        address.setEstablishment(null);
    }

    /**
     * Endereco principal da loja para o read-back (Partner BUG4).
     * Prioriza o marcado {@code isMain}; senao o primeiro cadastrado; {@code null} se nao houver.
     */
    public Address getMainAddress() {
        return addresses.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsMain()))
                .findFirst()
                .or(() -> addresses.stream().findFirst())
                .map(EstablishmentAddress::getAddress)
                .orElse(null);
    }

    /**
     * "Aberto agora" efetivo (BUG5 parte B): a loja so esta realmente recebendo
     * quando o {@code openStatus} e {@code OPEN} <b>e</b> o horario atual cai dentro
     * da janela {@code [openingTime, closingTime)}.
     * <p>
     * Campo de LEITURA derivado — nao muda o {@code openStatus} persistido (nao
     * ha auto-fechamento; fechar e sempre manual). Serve pro front-client decidir
     * se mostra a loja como aberta e (no futuro) pro gate de entrada na fila.
     * <p>
     * Sem janela definida ({@code openingTime}/{@code closingTime} nulos) so o
     * {@code openStatus} manda — nao ha como recortar por horario.
     */
    public boolean isOpenNow() {
        return isOpenAt(OffsetTime.now(java.time.ZoneOffset.UTC));
    }

    /**
     * Nucleo puro do {@link #isOpenNow()}, parametrizado pelo instante — deterministico
     * para teste. Trata janela normal {@code [open, close)}, 24h ({@code open == close})
     * e janela que vira a meia-noite ({@code close < open}).
     */
    public boolean isOpenAt(OffsetTime now) {
        if (openStatus != OpenStatus.OPEN) {
            return false;
        }
        if (openingTime == null || closingTime == null) {
            return true; // sem janela: so o open_status manda
        }
        java.time.LocalTime n = now.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalTime();
        java.time.LocalTime open = openingTime.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalTime();
        java.time.LocalTime close = closingTime.withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalTime();
        if (open.equals(close)) {
            return true; // janela degenerada = 24h
        }
        if (open.isBefore(close)) {
            return !n.isBefore(open) && n.isBefore(close); // [open, close)
        }
        // janela que atravessa a meia-noite: [open, 24h) U [0, close)
        return !n.isBefore(open) || n.isBefore(close);
    }
}
