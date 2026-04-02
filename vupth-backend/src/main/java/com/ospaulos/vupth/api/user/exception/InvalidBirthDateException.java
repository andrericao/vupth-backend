package com.ospaulos.vupth.api.user.exception;

import java.time.LocalDate;

/**
 * Thrown when a birthDate fails the minimum or maximum age check.
 *
 * Business rules (aligned with the youngest allowed platform user):
 *   MIN_AGE = 12  — below this the user is considered too young
 *   MAX_AGE = 100 — above this is considered an impossible/erroneous date
 *
 * @since v6
 */
public class InvalidBirthDateException extends RuntimeException {

    public static final int MIN_AGE = 12;
    public static final int MAX_AGE = 100;

    public InvalidBirthDateException(LocalDate birthDate, int actualAge) {
        super(String.format(
                "Birth date '%s' results in an age of %d years. Accepted range: %d–%d.",
                birthDate, actualAge, MIN_AGE, MAX_AGE));
    }

    public InvalidBirthDateException(LocalDate birthDate) {
        super(String.format(
                "Birth date '%s' is in the future.", birthDate));
    }
}
