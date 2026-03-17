package com.ospaulos.vupth.api.user.mapper;

import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.dto.response.auth.RefreshTokenResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosAndToken;
import com.ospaulos.vupth.api.user.dto.response.profile.UserProfileGeneralInfosResponse;
import com.ospaulos.vupth.api.user.dto.response.profile.UserRegisterResponse;
import com.ospaulos.vupth.api.user.dto.response.UserRoleResponse;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.UserRole;
import org.mapstruct.Mapper;

import java.util.Set;

@Mapper(componentModel = "spring")
public interface UserMapper {

    BaseUser toUserEntity(UserRegisterRequest request);

    UserRegisterResponse toRegisterResponse(BaseUser user);

    //BaseUser fromLoginRequestToEntity(LoginRequest request);

    UserProfileGeneralInfosAndToken toLoginResponse(UserProfileGeneralInfosResponse user, RefreshTokenResponse refreshToken);

    UserProfileGeneralInfosResponse toGeneralInfosUserResponse(BaseUser user);

    Set<UserRoleResponse> toUserRoleResponseSet(Set<UserRole> userRoles);
}
