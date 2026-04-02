package com.ospaulos.vupth.domain.user.repository;

import com.ospaulos.vupth.domain.user.model.UserAddress;

import java.util.List;
import java.util.UUID;

public interface UserAddressRepository{
    public List<UserAddress> findAllByBaseUser_Id(UUID userId);
}
