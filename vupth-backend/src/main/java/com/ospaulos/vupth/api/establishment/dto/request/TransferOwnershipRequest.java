package com.ospaulos.vupth.api.establishment.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TransferOwnershipRequest(
        @NotNull UUID newOwnerId
) {}
