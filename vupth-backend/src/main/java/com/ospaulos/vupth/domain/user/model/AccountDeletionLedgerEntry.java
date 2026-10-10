package com.ospaulos.vupth.domain.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Quem apagou a conta, sem dado pessoal (exclusao-apos-restaurar, 08/10, LGPD): so o SHA-256 do id e a
 * hora. O backup diario copia esta tabela para o bucket ({@code ledger/account-deletions.csv}); depois de
 * restaurar um backup antigo, o {@code scripts/reaplicar-exclusoes.sh} anonimiza de novo quem tinha apagado
 * a conta depois dele. O hash e o de {@code encode(sha256(id::text::bytea), 'hex')} no Postgres.
 */
@Getter
@Entity
@NoArgsConstructor
@Table(name = "account_deletion_ledger")
public class AccountDeletionLedgerEntry {

    @Id
    @Column(name = "id_hash", length = 64, nullable = false)
    private String idHash;

    @Column(name = "deleted_at", nullable = false)
    private OffsetDateTime deletedAt;

    public AccountDeletionLedgerEntry(UUID userId, OffsetDateTime deletedAt) {
        this.idHash = hash(userId);
        this.deletedAt = deletedAt;
    }

    /** SHA-256 do id em texto (o mesmo que o Postgres calcula no script de restauracao), em hexadecimal. */
    public static String hash(UUID userId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(userId.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel", e);
        }
    }
}
