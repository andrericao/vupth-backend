package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — campo apelido (nickname) na BaseUser via perfil.
 *
 * Ponto unico de escrita: PATCH /profile/general-infos.
 * Fonte canonica de leitura: GET /profile/me.
 *
 * Cobre:
 *  - PATCH /profile/general-infos define / edita / limpa (branco) / valida (>50 chars) o apelido
 *  - GET /profile/me retorna first_name / last_name / nickname
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfileNicknameIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String token, userId;

    @BeforeAll
    void setup() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.userId = user.id();
        this.token = user.token();
    }

    // ─────────────────────────────────────────────────────────────
    // PATCH /profile/general-infos — escrita do apelido
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("PATCH /profile/general-infos — 200 define o apelido (+ modified_fields)")
    void patchGeneralInfos_setsNickname() throws Exception {
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"first_name\":\"Roberto\",\"last_name\":\"Silva\",\"nickname\":\"Bibba\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Bibba"))
                .andExpect(jsonPath("$.first_name").value("Roberto"))
                .andExpect(jsonPath("$.modified_fields[?(@ == 'nickname')]").exists())
                .andDo(print());

        BaseUser u = userRepository.findById(UUID.fromString(userId)).orElseThrow();
        assertThat(u.getNickname()).isEqualTo("Bibba");
    }

    @Test
    @Order(11)
    @DisplayName("PATCH /profile/general-infos — 200 edita o apelido")
    void patchGeneralInfos_updatesNickname() throws Exception {
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"nickname\":\"Beto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Beto"))
                .andDo(print());

        BaseUser u = userRepository.findById(UUID.fromString(userId)).orElseThrow();
        assertThat(u.getNickname()).isEqualTo("Beto");
    }

    @Test
    @Order(12)
    @DisplayName("PATCH /profile/general-infos — 200 apelido em branco limpa para null")
    void patchGeneralInfos_blankNicknameClears() throws Exception {
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"nickname\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").doesNotExist())
                .andDo(print());

        BaseUser u = userRepository.findById(UUID.fromString(userId)).orElseThrow();
        assertThat(u.getNickname()).isNull();
    }

    @Test
    @Order(13)
    @DisplayName("PATCH /profile/general-infos — 422 apelido acima de 50 chars")
    void patchGeneralInfos_nicknameTooLong() throws Exception {
        String tooLong = "a".repeat(51);
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"nickname\":\"" + tooLong + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // GET /profile/me — leitura canonica
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("GET /profile/me — 200 retorna first_name / last_name / nickname")
    void getMe_returnsProfile() throws Exception {
        // garante um apelido conhecido
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"nickname\":\"Bob\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/profile/me")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.first_name").value("Roberto"))
                .andExpect(jsonPath("$.last_name").value("Silva"))
                .andExpect(jsonPath("$.nickname").value("Bob"))
                .andExpect(jsonPath("$.email").exists())
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("GET /profile/me — 401 sem token")
    void getMe_noToken_401() throws Exception {
        mvc.perform(get("/profile/me"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
