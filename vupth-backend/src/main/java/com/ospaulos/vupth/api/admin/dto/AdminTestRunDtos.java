package com.ospaulos.vupth.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** As telas de teste do painel (modo-piloto-sem-cobranca, DEC-106). Valores sao simbolicos: nada foi cobrado. */
public final class AdminTestRunDtos {

    private AdminTestRunDtos() {}

    public record StartRequest(@NotBlank @Size(max = 120) @JsonProperty("name") String name) {}

    public record Row(
            @JsonProperty("id") UUID id,
            @JsonProperty("name") String name,
            @JsonProperty("establishment_id") UUID establishmentId,
            @JsonProperty("establishment_name") String establishmentName,
            @JsonProperty("started_at") OffsetDateTime startedAt,
            @JsonProperty("ended_at") OffsetDateTime endedAt,
            @JsonProperty("active") boolean active,
            @JsonProperty("attendances") long attendances,
            @JsonProperty("symbolic_amount") BigDecimal symbolicAmount) {}

    public record AttendanceLine(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("status") String status,
            @JsonProperty("payment_status") String paymentStatus,
            @JsonProperty("symbolic_amount") BigDecimal symbolicAmount,
            @JsonProperty("scheduled_time") OffsetDateTime scheduledTime) {}

    public record Detail(
            @JsonProperty("run") Row run,
            @JsonProperty("attendances") java.util.List<AttendanceLine> attendances) {}
}
