package com.ospaulos.vupth.api.attendance.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** Corpo de {@code PATCH /attendances/{id}/service-items} (adicionar-servico-na-fila, Client C8). */
public record AddServiceItemsRequest(
        @NotEmpty(message = "serviceItemIds is required")
        List<UUID> serviceItemIds
) {}
