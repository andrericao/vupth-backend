package com.ospaulos.vupth.infrastructure.persistence.jpa.user.impl;

import com.ospaulos.vupth.domain.user.model.UserAddress;
import com.ospaulos.vupth.domain.user.repository.UserAddressRepository;
import com.ospaulos.vupth.infrastructure.persistence.jpa.user.UserAddressJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserAddressRepositoryAdapter implements UserAddressRepository {

    private final UserAddressJpaRepository jpaRepository;

    @Override
    public List<UserAddress> findAllByBaseUser_Id(UUID userId){
        return jpaRepository.findAllByBaseUser_Id(userId);
    }
}
