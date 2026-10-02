package com.ospaulos.vupth.integrations.interval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — refeicoes Modelo B (Partner S3, spec de 28/06).
 *
 * <p>Um "almoco" 4h atras nunca iniciado e uma "janta" daqui a 2h: o inicio maximo do almoco e a janta
 * menos 3h — uma hora atras —, entao ele esta SUPRIMIDO. A listagem marca {@code suppressedToday} e o
 * "iniciar refeicao" ({@code /next/start}) pula o almoco e comeca a janta.
 *
 * <p>Horarios relativos ao relogio, no dia da loja: fora das 04:00-21:59 o cenario cruzaria a meia-noite,
 * e o teste se abstem.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MealModelBIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    UUID profId, lunchId, dinnerId;
    String profToken;

    @BeforeAll
    void setup() throws Exception {
        LocalTime now = BusinessClock.nowTime();
        Assumptions.assumeTrue(now.isAfter(LocalTime.of(4, 0)) && now.isBefore(LocalTime.of(22, 0)),
                "cenario relativo ao relogio cruzaria a meia-noite");

        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = UUID.fromString(user.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, user.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, user.token(), estab.id());
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(user.email(), user.password()))))
                .andExpect(status().isOk())
                .andReturn();
        this.profToken = "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");

        intervalRepository.deleteAll(intervalRepository.findByProfessional_Id(profId));
        var professional = professionalRepository.findById(profId).orElseThrow();
        this.lunchId = intervalRepository.save(ProfessionalInterval.createRecurring(
                professional, 60, now.minusHours(4).withSecond(0).withNano(0), "DAILY", "Almoco")).getId();
        this.dinnerId = intervalRepository.save(ProfessionalInterval.createRecurring(
                professional, 60, now.plusHours(2).withSecond(0).withNano(0), "DAILY", "Janta")).getId();
    }

    @Test
    @Order(10)
    @DisplayName("GET intervals: o almoco que passou do inicio maximo vem suppressedToday=true; a janta, false")
    void list_marksSuppressed() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/professionals/{pid}/intervals", profId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andReturn();
        JSONArray list = new JSONArray(r.getResponse().getContentAsString());
        for (int i = 0; i < list.length(); i++) {
            JSONObject iv = list.getJSONObject(i);
            if (iv.getString("intervalId").equals(lunchId.toString())) {
                assertThat(iv.getBoolean("suppressedToday")).as("almoco suprimido").isTrue();
            } else if (iv.getString("intervalId").equals(dinnerId.toString())) {
                assertThat(iv.getBoolean("suppressedToday")).as("janta no prazo").isFalse();
            }
        }
    }

    @Test
    @Order(20)
    @DisplayName("PATCH intervals/next/start: pula o almoco suprimido e comeca a janta")
    void startNext_skipsSuppressed() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/next/start", profId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalId").value(dinnerId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @Order(30)
    @DisplayName("PATCH intervals/next/start: sem refeicao sobrando no dia -> 409 INTERVAL_CONFLICT")
    void startNext_noneLeft_409() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/next/start", profId)
                        .header("Authorization", profToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERVAL_CONFLICT"));
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
