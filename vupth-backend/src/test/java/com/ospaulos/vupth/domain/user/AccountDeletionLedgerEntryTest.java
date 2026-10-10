package com.ospaulos.vupth.domain.user;

import com.ospaulos.vupth.domain.user.model.AccountDeletionLedgerEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests — o hash do id de quem apagou a conta (exclusao-apos-restaurar). */
class AccountDeletionLedgerEntryTest {

    @Test
    @DisplayName("SHA-256 do id em texto, em hex — o mesmo que o Postgres calcula no script de restauracao")
    void hashMatchesPostgres() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        // encode(sha256('123e4567-e89b-12d3-a456-426614174000'::bytea), 'hex')
        assertThat(AccountDeletionLedgerEntry.hash(id))
                .isEqualTo("986c0dc956dc822b5d8f698661b9eb1ef880786ff9043c16744d2a420e99e9bb");
    }

    @Test
    @DisplayName("A entrada guarda so o hash e a hora")
    void entry() {
        UUID id = UUID.randomUUID();
        OffsetDateTime when = OffsetDateTime.now();
        AccountDeletionLedgerEntry entry = new AccountDeletionLedgerEntry(id, when);
        assertThat(entry.getIdHash()).isEqualTo(AccountDeletionLedgerEntry.hash(id)).hasSize(64).doesNotContain(id.toString());
        assertThat(entry.getDeletedAt()).isEqualTo(when);
    }
}
