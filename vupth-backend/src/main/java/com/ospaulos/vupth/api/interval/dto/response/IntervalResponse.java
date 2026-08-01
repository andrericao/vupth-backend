package com.ospaulos.vupth.api.interval.dto.response;

import com.ospaulos.vupth.domain.interval.valueObject.IntervalStatus;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;

import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.UUID;

public record IntervalResponse(
        UUID intervalId,
        IntervalType type,
        IntervalStatus status,
        Integer durationMinutes,
        OffsetDateTime startTime,
        OffsetDateTime endTime,
        String description,
        LocalTime fixedStartTime,
        String recurrencePattern)
{}
