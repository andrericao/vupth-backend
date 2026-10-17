package com.ospaulos.vupth.domain.admin.service;

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator;
import com.ospaulos.vupth.domain.admin.exception.AdminTotpException;
import com.ospaulos.vupth.domain.admin.model.AdminRecoveryCode;
import com.ospaulos.vupth.domain.admin.model.AdminTotp;
import com.ospaulos.vupth.domain.admin.repository.AdminRecoveryCodeRepository;
import com.ospaulos.vupth.domain.admin.repository.AdminTotpRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * O segundo fator do painel (painel-de-monitoramento, fatia 3): TOTP (RFC 6238, Google Authenticator) com 8 codigos
 * de recuperacao. Cadastro em dois tempos ({@link #setup} gera o segredo, {@link #confirm} o ativa com um codigo e
 * entrega os codigos de recuperacao uma vez so). Erros seguidos travam a conta: {@value #MAX_ATTEMPTS} falhas
 * bloqueiam por {@value #LOCK_MINUTES} min (429). Aceita o codigo do passo anterior e do seguinte (relogio torto).
 */
@Service
public class AdminTotpService {

    static final int MAX_ATTEMPTS = 5;
    static final int LOCK_MINUTES = 15;
    static final int RECOVERY_CODES = 8;
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final String ISSUER = "VUPTH";

    private final AdminTotpRepository totps;
    private final AdminRecoveryCodeRepository recoveryCodes;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public AdminTotpService(AdminTotpRepository totps, AdminRecoveryCodeRepository recoveryCodes) {
        this(totps, recoveryCodes, Clock.systemUTC());
    }

    AdminTotpService(AdminTotpRepository totps, AdminRecoveryCodeRepository recoveryCodes, Clock clock) {
        this.totps = totps;
        this.recoveryCodes = recoveryCodes;
        this.clock = clock;
    }

    public record Setup(String secret, String otpauthUri) {}

    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return totps.findById(userId).map(AdminTotp::isEnabled).orElse(false);
    }

    /** Gera o segredo (ou troca o de um cadastro nao confirmado). Com o segundo fator ja ativo: 409. */
    @Transactional
    public Setup setup(UUID userId, String email) {
        AdminTotp totp = totps.findById(userId).orElseGet(() -> {
            AdminTotp created = new AdminTotp();
            created.setUserId(userId);
            created.setCreatedAt(OffsetDateTime.now(clock));
            return created;
        });
        if (totp.isEnabled()) {
            throw new AdminTotpException(HttpStatus.CONFLICT, "ADMIN_TOTP_ALREADY_ENABLED",
                    "O segundo fator ja esta ativo nesta conta.");
        }
        byte[] raw = new byte[20];
        random.nextBytes(raw);
        String secret = base32(raw);
        totp.setSecret(secret);
        totps.save(totp);
        return new Setup(secret, "otpauth://totp/" + ISSUER + ":" + email + "?secret=" + secret + "&issuer=" + ISSUER);
    }

    /** Ativa o segundo fator com um codigo valido e devolve os codigos de recuperacao (so desta vez, em claro). */
    @Transactional(noRollbackFor = AdminTotpException.class)
    public List<String> confirm(UUID userId, String code) {
        AdminTotp totp = totps.findById(userId).orElseThrow(() -> new AdminTotpException(
                HttpStatus.CONFLICT, "ADMIN_TOTP_NOT_SET_UP", "Comece pelo cadastro do segundo fator."));
        if (totp.isEnabled()) {
            throw new AdminTotpException(HttpStatus.CONFLICT, "ADMIN_TOTP_ALREADY_ENABLED",
                    "O segundo fator ja esta ativo nesta conta.");
        }
        checkNotLocked(totp);
        if (!matches(totp.getSecret(), code)) {
            fail(totp);
        }
        totp.setEnabled(true);
        totp.setFailedAttempts(0);
        totp.setLockedUntil(null);
        totps.save(totp);

        recoveryCodes.deleteByUserId(userId);
        List<String> plain = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String recovery = recoveryCode();
            plain.add(recovery);
            AdminRecoveryCode row = new AdminRecoveryCode();
            row.setId(UUID.randomUUID());
            row.setUserId(userId);
            row.setCodeHash(hash(recovery));
            recoveryCodes.save(row);
        }
        return plain;
    }

    /** Confere o codigo do app (6 digitos) ou um codigo de recuperacao (usa e queima). Erro: 401; travada: 429. */
    @Transactional(noRollbackFor = AdminTotpException.class)
    public void verify(UUID userId, String codeOrRecovery) {
        AdminTotp totp = totps.findById(userId).filter(AdminTotp::isEnabled).orElseThrow(() -> new AdminTotpException(
                HttpStatus.CONFLICT, "ADMIN_TOTP_NOT_SET_UP", "O segundo fator ainda nao foi cadastrado."));
        checkNotLocked(totp);
        String code = codeOrRecovery == null ? "" : codeOrRecovery.trim();
        boolean ok;
        if (code.matches("\\d{6}")) {
            ok = matches(totp.getSecret(), code);
        } else {
            var recovery = recoveryCodes.findByUserIdAndCodeHashAndUsedAtIsNull(userId, hash(code));
            ok = recovery.isPresent();
            recovery.ifPresent(row -> {
                row.setUsedAt(OffsetDateTime.now(clock));
                recoveryCodes.save(row);
            });
        }
        if (!ok) {
            fail(totp);
        }
        totp.setFailedAttempts(0);
        totp.setLockedUntil(null);
        totps.save(totp);
    }

    private void checkNotLocked(AdminTotp totp) {
        if (totp.getLockedUntil() != null && totp.getLockedUntil().isAfter(OffsetDateTime.now(clock))) {
            throw new AdminTotpException(HttpStatus.TOO_MANY_REQUESTS, "ADMIN_TOTP_LOCKED",
                    "Muitas tentativas. Tente de novo em alguns minutos.");
        }
    }

    private void fail(AdminTotp totp) {
        int attempts = totp.getFailedAttempts() + 1;
        totp.setFailedAttempts(attempts);
        if (attempts >= MAX_ATTEMPTS) {
            totp.setLockedUntil(OffsetDateTime.now(clock).plusMinutes(LOCK_MINUTES));
            totp.setFailedAttempts(0);
        }
        totps.save(totp);
        throw new AdminTotpException(HttpStatus.UNAUTHORIZED, "ADMIN_TOTP_INVALID", "Codigo invalido.");
    }

    /** O codigo do passo atual, do anterior e do seguinte (30 s cada). */
    boolean matches(String base32Secret, String code) {
        if (code == null || !code.trim().matches("\\d{6}")) {
            return false;
        }
        try {
            TimeBasedOneTimePasswordGenerator generator = new TimeBasedOneTimePasswordGenerator();
            SecretKeySpec key = new SecretKeySpec(fromBase32(base32Secret), generator.getAlgorithm());
            Instant now = clock.instant();
            int wanted = Integer.parseInt(code.trim());
            for (int step = -1; step <= 1; step++) {
                if (generator.generateOneTimePassword(key, now.plus(Duration.ofSeconds(30L * step))) == wanted) {
                    return true;
                }
            }
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular o codigo TOTP", e);
        }
    }

    /** Dez letras e numeros sem os que se confundem, em dois blocos: {@code ABCDE-FGHIJ}. */
    private String recoveryCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                code.append('-');
            }
            code.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private static String hash(String recovery) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(recovery.trim().toUpperCase().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public static byte[] fromBase32(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : text.toUpperCase().toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
