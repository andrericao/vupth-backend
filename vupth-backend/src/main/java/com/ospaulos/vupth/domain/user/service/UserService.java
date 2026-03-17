package com.ospaulos.vupth.domain.user.service;

import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.domain.user.model.BaseUser;

import java.util.List;
import java.util.UUID;

public interface UserService {

    UserRegisterResponse findByEmail(String email);

    UserRegisterResponse findById(UUID id);

    List<BaseUser> findAll();
}
