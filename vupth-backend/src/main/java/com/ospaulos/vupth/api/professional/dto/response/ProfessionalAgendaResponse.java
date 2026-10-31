package com.ospaulos.vupth.api.professional.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * GET /professional/me/agenda?from=&to= (agenda-aberta-semana, DEC-117/130): os dias prontos para o Partner desenhar a agenda,
 * sem juntar tres chamadas. Cada dia traz o horario que vale de fato (loja ∩ agenda dele, semana e data especial ja aplicadas),
 * se foi o profissional que abriu aquele dia ({@code opened_by_me}) e os agendamentos dele naquele dia. Os dias que ele ja abriu
 * aparecem junto com o de hoje; o intervalo vai de hoje a, no maximo, {@value #MAX_DAYS} dias.
 */
public record ProfessionalAgendaResponse(List<Day> days) {

    public static final int MAX_DAYS = 31;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Day(LocalDate date,
                      boolean closed,
                      LocalTime opens,
                      LocalTime closes,
                      @JsonProperty("store_open") boolean storeOpen,
                      @JsonProperty("opened_by_me") boolean openedByMe,
                      List<Appointment> appointments) {}

    public record Appointment(UUID id,
                              OffsetDateTime start,
                              @JsonProperty("duration_minutes") int durationMinutes) {}
}
