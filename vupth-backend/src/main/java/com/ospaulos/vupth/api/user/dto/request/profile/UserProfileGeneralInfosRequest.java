package com.ospaulos.vupth.api.user.dto.request.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.user.model.valueObject.Gender;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;


import java.time.LocalDate;

@JsonIgnoreProperties(ignoreUnknown = false)
public record UserProfileGeneralInfosRequest(
        @JsonProperty("first_name")
        String firstName,

        @JsonProperty("last_name")
        String lastName,

        @Size(max = 50, message = "Nickname must be at most 50 characters")
        String nickname,

        String phone,

        @JsonProperty("birth_date")
        LocalDate birthDate,

        Gender gender,

        @JsonProperty("profile_image_url")
        String profileImageUrl
) {
    /** Backward-compat: callers sem apelido. */
    public UserProfileGeneralInfosRequest(String firstName, String lastName, String phone,
                                          LocalDate birthDate, Gender gender, String profileImageUrl) {
        this(firstName, lastName, null, phone, birthDate, gender, profileImageUrl);
    }
}