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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — validacoes de campo dos intervalos (spec item #11g):
 * teto de 240min em durationMinutes e recurrencePattern restrito a
 * {WEEKDAYS, DAILY, WEEKENDS}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalValidationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken, estabId;
    UUID profId, lunchId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = UUID.fromString(owner.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.estabId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());
        this.profToken = relogin(owner.email(), owner.password());

        this.lunchId = intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(profId))
                .filter(i -> i.getFixedStartTime() != null && i.getFixedStartTime().getHour() == 12)
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

    private void postRecurring(String body) throws Exception {
        mvc.perform(post("/api/v1/professionals/{pid}/intervals/recurring", profId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andDo(print());
    }

    @Test
    @Order(10)
    @DisplayName("POST recurring — duracao > 240 -> 422")
    void duration_overCap() throws Exception {
        postRecurring("{\"durationMinutes\":300,\"fixedStartTime\":\"15:00:00Z\",\"recurrencePattern\":\"WEEKDAYS\"}");
    }

    @Test
    @Order(11)
    @DisplayName("POST recurring — recurrencePattern invalido -> 422")
    void recurrencePattern_invalid() throws Exception {
        postRecurring("{\"durationMinutes\":30,\"fixedStartTime\":\"15:00:00Z\",\"recurrencePattern\":\"MONTHLY\"}");
    }

    @Test
    @Order(20)
    @DisplayName("POST recurring — recurrencePattern DAILY (valido) -> 201")
    void recurrencePattern_daily_ok() throws Exception {
        mvc.perform(post("/api/v1/professionals/{pid}/intervals/recurring", profId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"durationMinutes\":30,\"fixedStartTime\":\"15:30:00Z\",\"recurrencePattern\":\"DAILY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("RECURRENT"))
                .andExpect(jsonPath("$.recurrencePattern").value("DAILY"));
    }

    @Test
    @Order(30)
    @DisplayName("PATCH edit — duracao > 240 -> 422")
    void update_duration_overCap() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}", profId, lunchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"durationMinutes\":241}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @Order(40)
    @DisplayName("define horario de funcionamento 09:00-18:00 (setup do #11g2)")
    void setBusinessHours() throws Exception {
        mvc.perform(patch("/establishment/{id}/general-infos", estabId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"opening_time\":\"09:00:00Z\",\"closing_time\":\"18:00:00Z\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(41)
    @DisplayName("POST recurring — refeicao fora da janela (17:30/60min termina 18:30 > 18:00) -> 400")
    void recurring_outsideBusinessHours() throws Exception {
        mvc.perform(post("/api/v1/professionals/{pid}/intervals/recurring", profId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"durationMinutes\":60,\"fixedStartTime\":\"17:30:00Z\",\"recurrencePattern\":\"WEEKDAYS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTERVAL_INVALID_TIME"))
                .andDo(print());
    }

    @Test
    @Order(42)
    @DisplayName("POST recurring — refeicao dentro da janela (10:00/60min) -> 201")
    void recurring_withinBusinessHours() throws Exception {
        mvc.perform(post("/api/v1/professionals/{pid}/intervals/recurring", profId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"durationMinutes\":60,\"fixedStartTime\":\"10:00:00Z\",\"recurrencePattern\":\"WEEKDAYS\"}"))
                .andExpect(status().isCreated());
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
