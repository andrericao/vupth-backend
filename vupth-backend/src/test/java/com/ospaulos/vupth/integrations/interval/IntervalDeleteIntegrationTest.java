package com.ospaulos.vupth.integrations.interval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — DELETE /api/v1/professionals/{professionalId}/intervals/{intervalId}
 * (spec item #11f — remover uma refeicao que o profissional nao usa).
 *
 * Cobre: 404 por ownership, 403 (CLIENT), 204 remove (hard delete) e 404 ao remover de novo.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalDeleteIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profAToken, profBToken, clientToken;
    UUID profAId, profBId, dinnerAId;

    @BeforeAll
    void setup() throws Exception {
        var userA = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profAId = UUID.fromString(userA.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, userA.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, userA.token(), estab.id());
        this.profAToken = relogin(userA.email(), userA.password());

        var userB = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profBId = UUID.fromString(userB.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, userB.token(), estab.id());
        this.profBToken = relogin(userB.email(), userB.password());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        // remove a janta (18:00) — profissional que nao janta
        this.dinnerAId = intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(profAId))
                .filter(i -> i.getFixedStartTime() != null && i.getFixedStartTime().getHour() == 18)
                .map(ProfessionalInterval::getId)
                .findFirst().orElseThrow();
    }

    private String relogin(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");
    }

    @Test
    @Order(10)
    @DisplayName("DELETE — 404 intervalo de outro profissional (ownership)")
    void delete_ownershipMismatch() throws Exception {
        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}", profBId, dinnerAId)
                        .header("Authorization", profBToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"));

        assertThat(intervalRepository.findById(dinnerAId)).isPresent(); // nao apagou
    }

    @Test
    @Order(11)
    @DisplayName("DELETE — 403 CLIENT nao pode remover")
    void delete_clientForbidden() throws Exception {
        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}", profAId, dinnerAId)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(20)
    @DisplayName("DELETE — 204 remove a refeicao (hard delete)")
    void delete_success() throws Exception {
        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}", profAId, dinnerAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isNoContent())
                .andDo(print());

        assertThat(intervalRepository.findById(dinnerAId)).isEmpty();
    }

    @Test
    @Order(21)
    @DisplayName("DELETE — 404 ao remover de novo (ja removida)")
    void delete_again_notFound() throws Exception {
        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}", profAId, dinnerAId)
                        .header("Authorization", profAToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INTERVAL_NOT_FOUND"));
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
