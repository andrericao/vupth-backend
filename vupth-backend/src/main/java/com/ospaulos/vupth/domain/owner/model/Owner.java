package com.ospaulos.vupth.domain.owner.model;

import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name="OWNER")
public class Owner {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "id")
    private BaseUser baseUser;

    protected Owner() {}

    public Owner(BaseUser baseUser) {
        this.baseUser = baseUser;
        // O id NAO e atribuido aqui: o @MapsId o deriva do baseUser no persist. Com o id preenchido, o
        // Hibernate 6.6 trata o objeto novo como ja salvo e o merge em cascata falha ("Row was updated or
        // deleted by another transaction"), porque a linha ainda nao existe.
    }

    /** Antes do flush o campo ainda esta nulo; o id e sempre o do usuario (MapsId). */
    public UUID getId() {
        return id != null ? id : (baseUser != null ? baseUser.getId() : null);
    }

    @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL)
    private List<Establishment> establishments = new ArrayList<>();

    public void addEstablishment(Establishment establishment) {
        establishments.add(establishment);
        establishment.setOwner(this);
    }

    public void removeEstablishment(Establishment establishment) {
        establishments.remove(establishment);
        establishment.setOwner(null);
    }
}
