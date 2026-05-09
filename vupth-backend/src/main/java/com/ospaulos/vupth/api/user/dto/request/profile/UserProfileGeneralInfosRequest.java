package com.ospaulos.vupth.api.user.dto.request.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.user.model.valueObject.Gender;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = false)
public record UserProfileGeneralInfosRequest(
        @JsonProperty("first_name")
        String firstName,

        @JsonProperty("last_name")
        String lastName,

        String phone,

        @JsonProperty("birth_date")
        LocalDate birthDate,

        Gender gender,

        @JsonProperty("profile_image_url")
        String profileImageUrl
) {}