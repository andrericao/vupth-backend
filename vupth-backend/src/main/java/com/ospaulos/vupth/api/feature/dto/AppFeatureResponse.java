package com.ospaulos.vupth.api.feature.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.feature.AppFeature;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Uma chave no painel. {@code profiles}: {@code CLIENT}, {@code PROFESSIONAL} ou {@code ESTABLISHMENT}; lista vazia = todos. */
public record AppFeatureResponse(
        @JsonProperty("key") String key,
        @JsonProperty("description") String description,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("percent") int percent,
        @JsonProperty("profiles") List<String> profiles,
        @JsonProperty("regions") List<String> regions,
        @JsonProperty("accounts") List<String> accounts,
        @JsonProperty("depends_on") String dependsOn,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("updated_by") UUID updatedBy) {

    public static AppFeatureResponse of(AppFeature f) {
        return new AppFeatureResponse(f.getKey(), f.getDescription(), f.isEnabled(), f.getPercent(), f.profileList(), f.regionList(),
                f.accountList(), f.getDependsOn(), f.getUpdatedAt(), f.getUpdatedBy());
    }
}
