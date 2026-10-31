package com.ospaulos.vupth.api.professional.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * POST /professional/me/hours/open (agenda-aberta-semana, DEC-130): o profissional abre a agenda de varios dias de uma vez, ate
 * um mes. Dois jeitos, que podem vir juntos: {@code dates} (dias soltos, cada um com o horario que quiser) e
 * {@code repeat_default} (a semana cadastrada em {@code PUT /me/hours/weekly} copiada, de {@code from} a {@code to}, como dias
 * abertos; o dia que a semana fecha fica de fora). Nada amarra: pode abrir so alguns dias.
 */
public record OpenDaysRequest(
        List<Day> dates,
        @JsonProperty("repeat_default") Boolean repeatDefault,
        LocalDate from,
        LocalDate to) {

    public record Day(LocalDate date, LocalTime opens, LocalTime closes) {}
}
