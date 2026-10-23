package com.ospaulos.vupth.integrations.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.infrastructure.crypto.PersonalDataEncryptionMigrator;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** S4 (DEC-81, fase 1): a chave Pix e o titular ficam cifrados no banco e a API segue devolvendo o texto. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "vupth.security.data-encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "vupth.security.data-encryption.required=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersonalDataEncryptionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PersonalDataEncryptionMigrator migrator;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("salvar a chave grava cifrado no banco; o GET devolve em claro; o que estava em claro vira cifrado")
    void encryptedAtRestAndMigrated() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, user.token()).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, user.token(), establishmentId, "Beto");
        mvc.perform(put("/professional/me/pix").header("Authorization", user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_type\":\"PHONE\",\"key\":\"(11) 98765-4321\",\"holder_name\":\"Beto\"}"))
                .andExpect(status().isOk());

        UUID id = UUID.fromString(user.id());
        String key = jdbc.queryForObject("SELECT pix_key FROM base_user WHERE id = ?", String.class, id);
        String holder = jdbc.queryForObject("SELECT pix_holder_name FROM base_user WHERE id = ?", String.class, id);
        assertThat(key).startsWith("enc:v1:").doesNotContain("987654321");
        assertThat(holder).startsWith("enc:v1:").doesNotContain("Beto");
        mvc.perform(get("/professional/me/pix").header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("+5511987654321"))
                .andExpect(jsonPath("$.holder_name").value("Beto"));

        // texto puro de antes da migracao: continua legivel e a partida o cifra
        jdbc.update("UPDATE base_user SET pix_key = ?, pix_holder_name = ? WHERE id = ?", "+5511900000000", "Ana", id);
        mvc.perform(get("/professional/me/pix").header("Authorization", user.token()))
                .andExpect(jsonPath("$.key").value("+5511900000000"));
        assertThat(migrator.migrate()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pix_key FROM base_user WHERE id = ?", String.class, id))
                .startsWith("enc:v1:");
        mvc.perform(get("/professional/me/pix").header("Authorization", user.token()))
                .andExpect(jsonPath("$.key").value("+5511900000000"))
                .andExpect(jsonPath("$.holder_name").value("Ana"));
        assertThat(migrator.migrate()).isZero();
        migrator.onBoot();
    }

    /**
     * O banco e compartilhado entre as classes e as outras rodam sem a chave: o dado cifrado que sobra aqui quebra o
     * {@code userRepository.deleteAll()} de quem roda depois ("Dado cifrado no banco, mas DATA_ENCRYPTION_KEY nao esta
     * configurada"), conforme a ordem das classes. Limpa no fim.
     */
    @AfterAll
    void clearEncryptedData() {
        jdbc.update("UPDATE base_user SET pix_key = NULL, pix_holder_name = NULL"
                + " WHERE pix_key LIKE 'enc:v1:%' OR pix_holder_name LIKE 'enc:v1:%'");
    }
}
