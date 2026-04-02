package com.ospaulos.vupth.api.user.dto.response.profile;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.api.user.dto.response.UserRoleResponse;
import com.ospaulos.vupth.domain.user.model.valueObject.Gender;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record UserProfileGeneralInfosResponse(
        @JsonProperty("id")
        UUID id,

        @JsonProperty("first_name")
        String firstName,

        @JsonProperty("last_name")
        String lastName,

        String email,

        String phone,

        @JsonProperty("birth_date")
        LocalDate birthDate,

        Gender gender,

        @JsonProperty("profile_image_url")
        String profileImageUrl,

        @JsonProperty("created_at")
        OffsetDateTime createdAt,

        @JsonProperty("user_roles")
        Set<UserRoleResponse> roles,

        /**
         * Lists the fields that were effectively updated in this request.
         * An empty list means the request body contained no recognized field
         * with a new value (e.g. only unknown fields like "name" were sent).
         */
        @JsonProperty("modified_fields")
        List<String> modifiedFields
) {
}
