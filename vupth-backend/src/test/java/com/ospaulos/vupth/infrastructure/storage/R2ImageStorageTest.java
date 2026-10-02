package com.ospaulos.vupth.infrastructure.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** O R2_ACCOUNT_ID aceita o ID puro ou o que a Cloudflare mostra no campo "S3 API" do bucket. */
class R2ImageStorageTest {

    private static final String ID = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d";

    @Test
    @DisplayName("ID puro, com espacos, URL inteira do campo S3 API ou so o host: sempre o ID")
    void normalizeAccountId() {
        assertThat(R2ImageStorage.normalizeAccountId(ID)).isEqualTo(ID);
        assertThat(R2ImageStorage.normalizeAccountId("  " + ID + " ")).isEqualTo(ID);
        assertThat(R2ImageStorage.normalizeAccountId("https://" + ID + ".r2.cloudflarestorage.com/vupth-profile-photos-staging"))
                .isEqualTo(ID);
        assertThat(R2ImageStorage.normalizeAccountId(ID + ".r2.cloudflarestorage.com")).isEqualTo(ID);
    }
}
