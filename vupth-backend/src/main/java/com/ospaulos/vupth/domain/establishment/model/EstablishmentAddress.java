package com.ospaulos.vupth.domain.establishment.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.domain.address.model.Address;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name="ESTABLISHMENT_ADDRESSES")
public class EstablishmentAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference
    @JoinColumn(name = "establishment_id")
    private Establishment establishment;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "address_id", nullable = false)
    private Address address;

    @Column(name = "is_main")
    private Boolean isMain = false;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "business_hours")
    private String businessHours;

    protected EstablishmentAddress() {}

    public EstablishmentAddress(Address address){
        this.address = address;
    }
}
