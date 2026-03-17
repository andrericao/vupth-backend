package com.ospaulos.vupth.api.user.dto.request.profile;

import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record UpdateRoleRequest(
        @NotEmpty
        Set<RoleType> roles
) {}
