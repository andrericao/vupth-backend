package com.ospaulos.vupth.domain.events;

import com.ospaulos.vupth.domain.user.model.valueObject.Gender;

import java.time.LocalDate;

public record CreatePersonCommand(
        String name,
        String email,
        String password,
        String phone,
        LocalDate birthDate,
        Gender gender
) {
}
