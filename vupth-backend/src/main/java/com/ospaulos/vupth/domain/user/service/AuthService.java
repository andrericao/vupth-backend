package com.ospaulos.vupth.domain.user.service;

import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;

public interface AuthService {
    UserRegisterResponse authRegisterUserService(UserRegisterRequest requestDTO);
}
