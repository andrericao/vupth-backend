package com.ospaulos.vupth.domain.events;

import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IntervalCreatedEvent(
        ProfessionalInterval interval,
        String eventType,
        OffsetDateTime timestamp,
        IntervalType intervalType,
        String description
) {
    public IntervalCreatedEvent(ProfessionalInterval interval, IntervalType intervalType) {
        this(
                interval,
                "INTERVAL_CREATED",
                OffsetDateTime.now(),
                intervalType,
                "Professional interval created: " + interval.getDescription()
        );
    }

    // Utility methods
    public UUID getProfessionalId() {
        return interval.getProfessional().getId();
    }

    public UUID getEstablishmentId() {
        return interval.getProfessional().getEstablishment().getId();
    }
}
