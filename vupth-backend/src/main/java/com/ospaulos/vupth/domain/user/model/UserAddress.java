package com.ospaulos.vupth.domain.user.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.ospaulos.vupth.api.address.dto.request.UpdateAddressRequest;
import com.ospaulos.vupth.domain.address.model.Address;
import com.ospaulos.vupth.domain.address.valueObject.AddressType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@NoArgsConstructor
@Table(name="USER_ADDRESSES")
public class UserAddress{

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, optional = false)
    @JoinColumn(name = "address_id", nullable = false, unique = true)
    private Address address;

    @ManyToOne(fetch = FetchType.LAZY)
    @JsonBackReference("user-address")
    @JoinColumn(name = "user_id", nullable = false)
    private BaseUser baseUser;

    @Column(name = "is_main")
    private Boolean isMain = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "address_type")
    private AddressType type;

    public UserAddress(Address address){
        this.address = address;
    }

    protected void setBaseUser(BaseUser baseUser){
        this.baseUser = baseUser;
    }

    public void update(UpdateAddressRequest request){
        if(request.isMain() != null){
            this.isMain = request.isMain();
        }

        if(request.type() != null){
            this.type = request.type();
        }

        this.address.update(request);
    }

    public void updateAddress(UpdateAddressRequest address){

        if(this.address == null){
            throw new RuntimeException("User haven't address registered!");
        }

        this.address.update(address);
    }
}