package com.ospaulos.vupth.api.user.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.user.model.UserRole;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;

public record UserRoleResponse(
        @JsonProperty("role_type") RoleType roleType,
        @JsonProperty("is_active") Boolean isActive
) {}
