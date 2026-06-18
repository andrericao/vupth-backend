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
import org.mapstruct.Mapping;

import java.util.Collections;
import java.util.List;
import java.util.Set;

@Mapper(componentModel = "spring")
public interface UserMapper {

    BaseUser toUserEntity(UserRegisterRequest request);

    UserRegisterResponse toRegisterResponse(BaseUser user);

    UserProfileGeneralInfosAndToken toLoginResponse(UserProfileGeneralInfosResponse user, RefreshTokenResponse refreshToken);

    /**
     * MapStruct-generated mapping — modifiedFields defaults to empty list.
     * Use the overload below whenever you have the list from updateIfPresent().
     */
    @Mapping(target = "modifiedFields", expression = "java(java.util.Collections.emptyList())")
    UserProfileGeneralInfosResponse toGeneralInfosUserResponse(BaseUser user);

    /**
     * Maps BaseUser → UserProfileGeneralInfosResponse and attaches the list of fields
     * that were effectively modified in the current request.
     * Called by UpdateProfileGeneralInfosUseCase after updateIfPresent().
     */
    default UserProfileGeneralInfosResponse toGeneralInfosUserResponse(BaseUser user, List<String> modifiedFields) {
        UserProfileGeneralInfosResponse base = toGeneralInfosUserResponse(user);
        return new UserProfileGeneralInfosResponse(
                base.id(),
                base.firstName(),
                base.lastName(),
                base.nickname(),
                base.email(),
                base.phone(),
                base.birthDate(),
                base.gender(),
                base.profileImageUrl(),
                base.createdAt(),
                base.roles(),
                modifiedFields != null ? modifiedFields : Collections.emptyList()
        );
    }

    Set<UserRoleResponse> toUserRoleResponseSet(Set<UserRole> userRoles);
}
