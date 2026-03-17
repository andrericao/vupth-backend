package com.ospaulos.vupth.domain.address.model;

import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name="ADDRESSES")
public class Address {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String street;

    @Column(nullable = false, length = 10)
    @Size(max = 10)
    private String number;

    private String complement;

    @Column(nullable = false)
    private String neighborhood;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false, length = 2)
    private String state;

    @Column(nullable = false, length = 11)
    @Pattern(regexp = "^\\d{2}.\\d{3}-\\d{3}", message = "Invalid ZIP code. Enter only 8 digits.")
    private String zipCode;

    private String reference;

    //@Column(nullable = false)
    private Double latitude;

    //@Column(nullable = false)
    private Double longitude;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public void update(UpdateAddressRequest request){

        if(request.street() != null && !request.street().isBlank()){
            this.street = request.street();
        }

        if(request.number() != null && !request.number().isBlank()) {
            this.number = request.number();
        }

        if(request.complement() != null && !request.complement().isBlank()){
            this.complement = request.complement();
        }

        if(request.neighborhood() != null && !request.neighborhood().isBlank()){
            this.neighborhood = request.neighborhood();
        }

        if(request.city() != null && !request.city().isBlank()){
            this.city = request.city();
        }

        if(request.state() != null && !request.state().isBlank()){
            this.state = request.state();
        }

        if(request.zipCode() != null && !request.zipCode().isBlank()){
            this.zipCode = request.zipCode();
        }

        if(request.reference() != null && !request.reference().isBlank()){
            this.reference = request.reference();
        }

        if(request.latitude() != null){
            this.latitude = request.latitude();
        }

        if(request.longitude() != null){
            this.longitude = request.longitude();
        }
    }
}