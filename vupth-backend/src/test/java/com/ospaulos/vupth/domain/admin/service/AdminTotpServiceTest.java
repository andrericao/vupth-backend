package com.ospaulos.vupth.domain.admin.service;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.ospaulos.vupth.domain.admin.exception.AdminTotpException;
import com.ospaulos.vupth.domain.admin.model.AdminRecoveryCode;
import com.ospaulos.vupth.domain.admin.model.AdminTotp;
import com.ospaulos.vupth.domain.admin.repository.AdminRecoveryCodeRepository;
import com.ospaulos.vupth.domain.admin.repository.AdminTotpRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** painel, fatia 3: o segundo fator TOTP, os codigos de recuperacao e a trava por tentativas. */
class AdminTotpServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-17T15:00:00Z");

    private final AdminTotpRepository totps = mock(AdminTotpRepository.class);
    private final AdminRecoveryCodeRepository recovery = mock(AdminRecoveryCodeRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AdminTotpService service;
    private final UUID user = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminTotpService(totps, recovery, clock);
    }

    private static String codeAt(String secret, Instant at) throws Exception {
        var generator = new TimeBasedOneTimePasswordGenerator();
        int code = generator.generateOneTimePassword(
                new SecretKeySpec(AdminTotpService.fromBase32(secret), generator.getAlgorithm()), at);
        return String.format("%06d", code);
    }

    private AdminTotp enabled(String secret) {
        AdminTotp totp = new AdminTotp();
        totp.setUserId(user);
        totp.setSecret(secret);
        totp.setEnabled(true);
        when(totps.findById(user)).thenReturn(Optional.of(totp));
        return totp;
    }

    @Test
    @DisplayName("base32 vai e volta; o setup devolve o segredo e a URI para o app")
    void setupAndBase32() {
        byte[] data = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertThat(AdminTotpService.fromBase32(AdminTotpService.base32(data))).isEqualTo(data);

        when(totps.findById(user)).thenReturn(Optional.empty());
        var setup = service.setup(user, "ana@vupth.test");

        assertThat(setup.secret()).hasSize(32).matches("[A-Z2-7]+");
        assertThat(setup.otpauthUri()).startsWith("otpauth://totp/VUPTH:ana@vupth.test?secret=" + setup.secret())
                .endsWith("issuer=VUPTH");
        verify(totps).save(any(AdminTotp.class));
    }

    @Test
    @DisplayName("setup com o segundo fator ja ativo: 409; confirm sem setup: 409")
    void conflicts() {
        enabled("JBSWY3DPEHPK3PXP");
        assertThatThrownBy(() -> service.setup(user, "a@b.c")).isInstanceOfSatisfying(AdminTotpException.class,
                e -> assertThat(e.getCode()).isEqualTo("ADMIN_TOTP_ALREADY_ENABLED"));
        assertThatThrownBy(() -> service.confirm(user, "123456")).isInstanceOf(AdminTotpException.class);

        UUID other = UUID.randomUUID();
        when(totps.findById(other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(other, "123456")).isInstanceOfSatisfying(AdminTotpException.class,
                e -> assertThat(e.getCode()).isEqualTo("ADMIN_TOTP_NOT_SET_UP"));
        assertThatThrownBy(() -> service.verify(other, "123456")).isInstanceOf(AdminTotpException.class);
        assertThat(service.isEnabled(other)).isFalse();
    }

    @Test
    @DisplayName("confirm com o codigo certo ativa e entrega 8 codigos de recuperacao (so o hash e gravado)")
    void confirm() throws Exception {
        String secret = "JBSWY3DPEHPK3PXP";
        AdminTotp totp = new AdminTotp();
        totp.setUserId(user);
        totp.setSecret(secret);
        when(totps.findById(user)).thenReturn(Optional.of(totp));

        List<String> codes = service.confirm(user, codeAt(secret, NOW));

        assertThat(totp.isEnabled()).isTrue();
        assertThat(codes).hasSize(8).allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}")).doesNotHaveDuplicates();
        ArgumentCaptor<AdminRecoveryCode> saved = ArgumentCaptor.forClass(AdminRecoveryCode.class);
        verify(recovery, times(8)).save(saved.capture());
        assertThat(saved.getAllValues()).allMatch(r -> r.getCodeHash().length() == 64 && !codes.contains(r.getCodeHash()));
    }

    @Test
    @DisplayName("verify aceita o passo anterior e o seguinte do relogio, mas nao um de dois passos atras")
    void window() throws Exception {
        String secret = "JBSWY3DPEHPK3PXP";
        enabled(secret);

        service.verify(user, codeAt(secret, NOW));
        service.verify(user, codeAt(secret, NOW.minus(Duration.ofSeconds(30))));
        service.verify(user, codeAt(secret, NOW.plus(Duration.ofSeconds(30))));
        assertThatThrownBy(() -> service.verify(user, codeAt(secret, NOW.minus(Duration.ofSeconds(90)))))
                .isInstanceOfSatisfying(AdminTotpException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("codigo de recuperacao vale uma vez; o hash e o do texto em maiusculas")
    void recoveryCodeOnce() throws Exception {
        enabled("JBSWY3DPEHPK3PXP");
        AdminRecoveryCode row = new AdminRecoveryCode();
        when(recovery.findByUserIdAndCodeHashAndUsedAtIsNull(any(), any())).thenReturn(Optional.of(row))
                .thenReturn(Optional.empty());

        service.verify(user, "abcde-fghjk");

        assertThat(row.getUsedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThatThrownBy(() -> service.verify(user, "ABCDE-FGHJK")).isInstanceOf(AdminTotpException.class);
    }

    @Test
    @DisplayName("5 erros travam por 15 minutos (429); depois do prazo volta; acerto zera a conta")
    void lockout() throws Exception {
        String secret = "JBSWY3DPEHPK3PXP";
        AdminTotp totp = enabled(secret);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.verify(user, "000000")).isInstanceOfSatisfying(AdminTotpException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        }
        assertThat(totp.getLockedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(15)).atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> service.verify(user, codeAt(secret, NOW))).isInstanceOfSatisfying(
                AdminTotpException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));

        totp.setLockedUntil(NOW.minusSeconds(1).atOffset(ZoneOffset.UTC));
        totp.setFailedAttempts(3);
        service.verify(user, codeAt(secret, NOW));
        assertThat(totp.getFailedAttempts()).isZero();
        assertThat(totp.getLockedUntil()).isNull();
    }
}
