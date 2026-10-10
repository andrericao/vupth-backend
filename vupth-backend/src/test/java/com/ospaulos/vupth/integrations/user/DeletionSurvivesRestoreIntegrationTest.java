package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.profile.DeleteAccountRequest;
import com.ospaulos.vupth.domain.notification.model.DeviceToken;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.user.model.AccountDeletionLedgerEntry;
import com.ospaulos.vupth.domain.user.repository.AccountDeletionLedgerRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — a exclusao de conta sobrevive a restauracao do backup (exclusao-apos-restaurar, LGPD).
 *
 * <p>Apagar a conta grava o SHA-256 do id no ledger. Um backup antigo "volta" (a conta reaparece viva, sem
 * a linha do ledger); o {@code scripts/reaplicar-exclusoes.sql}, alimentado pelo ledger do bucket, anonimiza
 * de novo.</p>
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DeletionSurvivesRestoreIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AccountDeletionLedgerRepository ledgerRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;

    private final Faker faker = new Faker();

    /** Roda o script como o reaplicar-exclusoes.sh: numa transacao, com o ledger_import carregado. */
    private void reapply(String idHash, OffsetDateTime deletedAt) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            conn.createStatement().execute(
                    "CREATE TEMP TABLE ledger_import (id_hash VARCHAR(64) PRIMARY KEY, deleted_at TIMESTAMPTZ NOT NULL) ON COMMIT DROP");
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO ledger_import VALUES (?, ?)")) {
                ps.setString(1, idHash);
                ps.setTimestamp(2, Timestamp.from(deletedAt.toInstant()));
                ps.executeUpdate();
            }
            ScriptUtils.executeSqlScript(conn, new FileSystemResource("scripts/reaplicar-exclusoes.sql"));
            conn.commit();
        }
    }

    @Test
    @DisplayName("Apagar grava o hash no ledger; depois do restore, o script anonimiza de novo (e repetir nao quebra)")
    void deletionIsReappliedAfterRestore() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID userId = UUID.fromString(user.id());
        mvc.perform(delete("/profile/delete/me").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token())
                        .content(mapper.writeValueAsString(new DeleteAccountRequest(user.password()))))
                .andExpect(status().isNoContent());

        String hash = AccountDeletionLedgerEntry.hash(userId);
        AccountDeletionLedgerEntry entry = ledgerRepository.findById(hash).orElseThrow();
        OffsetDateTime deletedAt = entry.getDeletedAt();

        // O backup antigo volta: a conta viva, com os dados pessoais, um aparelho, e sem a linha do ledger.
        jdbc.update("UPDATE base_user SET deleted_at = NULL, is_active = TRUE, first_name = 'Ana', email = ? WHERE id = ?",
                user.email(), userId);
        deviceTokenRepository.save(new DeviceToken(userId, "ExponentPushToken[" + UUID.randomUUID() + "]", "android"));
        ledgerRepository.deleteById(hash);

        reapply(hash, deletedAt);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT first_name, email, is_active, deleted_at FROM base_user WHERE id = ?", userId);
        assertThat(row.get("first_name")).isEqualTo("Usuario");
        assertThat(row.get("email")).isEqualTo("deleted-" + userId + "@deleted.invalid");
        assertThat(row.get("is_active")).isEqualTo(false);
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(deviceTokenRepository.findByUserId(userId)).isEmpty();
        assertThat(ledgerRepository.existsById(hash)).isTrue();

        reapply(hash, deletedAt);
        assertThat(jdbc.queryForObject("SELECT email FROM base_user WHERE id = ?", String.class, userId))
                .isEqualTo("deleted-" + userId + "@deleted.invalid");
    }

    @Test
    @DisplayName("Conta que nao apagou nao e tocada pelo script")
    void othersAreUntouched() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID userId = UUID.fromString(user.id());

        reapply(AccountDeletionLedgerEntry.hash(UUID.randomUUID()), OffsetDateTime.now());

        Map<String, Object> row = jdbc.queryForMap("SELECT email, deleted_at FROM base_user WHERE id = ?", userId);
        assertThat((String) row.get("email")).doesNotStartWith("deleted-");
        assertThat(row.get("deleted_at")).isNull();
    }
}
