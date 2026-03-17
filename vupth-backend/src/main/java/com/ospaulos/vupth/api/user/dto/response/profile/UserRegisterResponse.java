package com.ospaulos.vupth.api.user.dto.response.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.user.dto.response.UserRoleResponse;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record UserRegisterResponse(
        @JsonProperty("id")
        UUID id,

        String email,

        @JsonProperty("created_at")
        OffsetDateTime createdAt,

        @JsonProperty("user_roles")
        Set<UserRoleResponse> roles
        ) {
}