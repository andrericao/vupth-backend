package com.ospaulos.vupth.api.affiliation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateAffiliationInvitationDTO(
        @NotNull UUID professionalId
) {}
