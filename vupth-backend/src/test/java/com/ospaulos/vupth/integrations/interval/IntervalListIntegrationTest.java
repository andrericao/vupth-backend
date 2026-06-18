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

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — GET /api/v1/professionals/{professionalId}/intervals.
 *
 * Garante que as refeicoes recorrentes PREVISTAS (seed, ainda nao iniciadas,
 * isActive=false) aparecem no GET — pre-requisito do front para listar/editar
 * almoco e janta e mostrar o botao "iniciar". Tambem cobre a refeicao iniciada.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalListIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken;
    UUID profId, lunchId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = UUID.fromString(owner.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
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

    @Test
    @Order(10)
    @DisplayName("GET — refeicoes previstas (seed) aparecem como SCHEDULED/RECURRENT")
    void previstas_appearInGet() throws Exception {
        // ordenado por fixedStartTime ASC: [0]=almoco (12:00), [1]=janta (18:00)
        mvc.perform(get("/api/v1/professionals/{pid}/intervals", profId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("RECURRENT"))
                .andExpect(jsonPath("$[0].status").value("SCHEDULED"))
                .andExpect(jsonPath("$[0].startTime").value(nullValue()))
                .andExpect(jsonPath("$[0].fixedStartTime", startsWith("12:00")))
                .andExpect(jsonPath("$[1].status").value("SCHEDULED"))
                .andExpect(jsonPath("$[1].fixedStartTime", startsWith("18:00")))
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("GET — refeicao iniciada vira ACTIVE, ambas continuam na lista")
    void started_isActiveInGet() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profId, lunchId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/professionals/{pid}/intervals", profId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.intervalId == '" + lunchId + "')].status").value("ACTIVE"))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
