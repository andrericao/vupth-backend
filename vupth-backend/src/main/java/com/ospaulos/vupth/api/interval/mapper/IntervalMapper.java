package com.ospaulos.vupth.api.interval.mapper;

import com.ospaulos.vupth.domain.events.CreateRecurringIntervalCommand;
import com.ospaulos.vupth.domain.events.CreateUnexpectedIntervalCommand;
import com.ospaulos.vupth.api.interval.dto.request.CreateRecurringIntervalRequest;
import com.ospaulos.vupth.api.interval.dto.request.CreateUnexpectedIntervalRequest;
import com.ospaulos.vupth.api.interval.dto.response.IntervalResponse;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.UUID;

@Mapper(componentModel = "spring")
public interface IntervalMapper {

    @Mapping(source = "id", target = "intervalId")
    @Mapping(source = "intervalType", target = "type")
    @Mapping(source = "plannedEndTime", target = "endTime")
    @Mapping(target = "status", expression = "java(interval.getIsActive() ? com.ospaulos.vupth.domain.interval.valueObject.IntervalStatus.ACTIVE : com.ospaulos.vupth.domain.interval.valueObject.IntervalStatus.SCHEDULED)")
    IntervalResponse toResponse(ProfessionalInterval interval);

    List<IntervalResponse> toResponseList(List<ProfessionalInterval> intervals);

    CreateRecurringIntervalCommand toCommand(UUID professionalId, CreateRecurringIntervalRequest request);
    CreateUnexpectedIntervalCommand toCommand(UUID professionalId, CreateUnexpectedIntervalRequest request);
}
