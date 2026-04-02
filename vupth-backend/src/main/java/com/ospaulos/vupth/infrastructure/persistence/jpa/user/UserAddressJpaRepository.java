package com.ospaulos.vupth.infrastructure.persistence.jpa.user;

import com.ospaulos.vupth.domain.user.model.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserAddressJpaRepository extends JpaRepository<UserAddress, UUID> {

    public List<UserAddress> findAllByBaseUser_Id(UUID userId);
}
