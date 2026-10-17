package com.ospaulos.vupth.unit.user;

import com.ospaulos.vupth.api.user.exception.PasswordPolicyException;
import com.ospaulos.vupth.api.user.policy.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** senha-limite-claro: de 6 a 64 caracteres e ate 72 bytes (o teto do BCrypt), acento contando 2. */
class PasswordPolicyTest {

    @Test
    @DisplayName("6 a 64 caracteres passa; 54 passa; 64 'a' passa")
    void accepts() {
        assertThatCode(() -> PasswordPolicy.check("abc123")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.check("a".repeat(54))).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.check("a".repeat(64))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("curta ou nula: PASSWORD_TOO_SHORT; 65 caracteres ou 37 'e' com acento (74 bytes): PASSWORD_TOO_LONG")
    void rejects() {
        assertThatThrownBy(() -> PasswordPolicy.check("abc12")).isInstanceOfSatisfying(PasswordPolicyException.class,
                e -> assertThat(e.getCode()).isEqualTo("PASSWORD_TOO_SHORT"));
        assertThatThrownBy(() -> PasswordPolicy.check(null)).isInstanceOf(PasswordPolicyException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(65))).isInstanceOfSatisfying(PasswordPolicyException.class,
                e -> {
                    assertThat(e.getCode()).isEqualTo("PASSWORD_TOO_LONG");
                    assertThat(e.getMessage()).contains("64");
                });
        assertThatThrownBy(() -> PasswordPolicy.check("é".repeat(37))).isInstanceOf(PasswordPolicyException.class);
        assertThat(PasswordPolicy.fitsBcrypt("é".repeat(36))).isTrue();
        assertThat(PasswordPolicy.fitsBcrypt("é".repeat(37))).isFalse();
    }
}
