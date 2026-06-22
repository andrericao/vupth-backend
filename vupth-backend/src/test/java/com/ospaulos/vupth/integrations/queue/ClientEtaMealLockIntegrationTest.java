package com.ospaulos.vupth.integrations.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test — LOCK da refeicao no ingresso (item #37).
 *
 * O que diferencia do pre-requisito (ETA meal-aware do snapshot): aqui se prova que a refeicao
 * travada no ingresso do cliente <b>nao sai do ETA INDIVIDUAL dele por ritmo da fila</b>. O
 * snapshot da loja (projecao) tiraria a refeicao quando o cliente vira o primeiro da fila; o
 * lock a mantem.
 *
 * Estrategia deterministica (independe da hora do relogio): refeicao recorrente as 00:01 — sempre
 * projetada para "agora" enquanto houver >=1 atendimento a frente. Cliente B entra em 2o lugar
 * (refeicao travada no ingresso). Ao cancelar o cliente A, B vira o 1o da fila: a projecao daria 0,
 * mas o ETA individual de B continua == duracao da refeicao (lock). Depois o skip (#41) derruba a
 * refeicao mesmo travada (override), e o unskip a restaura.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientEtaMealLockIntegrationTest {

    private static final int MEAL_MINUTES = 25;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired ProfessionalIntervalRepository intervalRepository;

    private final Faker faker = new Faker();

    String profToken;
    UUID profId, establishmentUuid, mealId;

    String tokenA, tokenB;
    UUID clientUuidA, clientUuidB;
    String attendanceIdA, attendanceIdB;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = UUID.fromString(owner.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentUuid = UUID.fromString(estab.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());
        this.profToken = relogin(owner.email(), owner.password());

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        var clientA = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientUuidA = UUID.fromString(clientA.id());
        this.tokenA = clientA.token();

        var clientB = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientUuidB = UUID.fromString(clientB.id());
        this.tokenB = clientB.token();
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

    /** Junta o cliente na fila; scheduledTime controla a ordem (ASC). Retorna o attendanceId. */
    private String enqueue(UUID clientUuid, String token, int scheduledInMinutes) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                profId, establishmentUuid, clientUuid, serviceItemIds,
                AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(scheduledInMinutes), 30,
                OffsetDateTime.now());
        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(created.getResponse().getContentAsString()).getString("id");
    }

    /** ETA individual estavel do cliente (lock-aware). */
    private int waitTime(String attendanceId, UUID clientUuid, String token) throws Exception {
        MvcResult res = mvc.perform(get("/client-queue/{attendanceId}/wait-time", attendanceId)
                        .param("clientId", clientUuid.toString())
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(res.getResponse().getContentAsString()).getInt("estimatedWaitingTime");
    }

    @Test
    @Order(10)
    @DisplayName("Setup: refeicao recorrente (sempre projetada p/ agora) + A (1o) e B (2o) na fila")
    void setupMealAndQueue() throws Exception {
        // Remove as refeicoes semeadas para isolar UMA refeicao controlada (sempre conta).
        intervalRepository.deleteAll(intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(profId))
                .toList());

        MvcResult meal = mvc.perform(post("/api/v1/professionals/{pid}/intervals/recurring", profId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"durationMinutes\":" + MEAL_MINUTES
                                + ",\"fixedStartTime\":\"00:01:00Z\",\"recurrencePattern\":\"DAILY\""
                                + ",\"description\":\"Cafe\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        this.mealId = UUID.fromString(new JSONObject(meal.getResponse().getContentAsString()).getString("intervalId"));

        // A entra primeiro (scheduledTime menor), B logo depois.
        this.attendanceIdA = enqueue(clientUuidA, tokenA, 10);
        this.attendanceIdB = enqueue(clientUuidB, tokenB, 20);
    }

    @Test
    @Order(20)
    @DisplayName("No ingresso de B (2o lugar) a refeicao a frente e travada — entra no ETA dele")
    void mealLockedAtIngress() throws Exception {
        int etaB = waitTime(attendanceIdB, clientUuidB, tokenB);
        // B tem A (1 atendimento) + a refeicao travada a frente → ETA >= duracao da refeicao.
        assertThat(etaB)
                .as("ETA de B inclui a refeicao travada no ingresso")
                .isGreaterThanOrEqualTo(MEAL_MINUTES);
    }

    @Test
    @Order(30)
    @DisplayName("Lock estavel: A cancela e B vira 1o; projecao daria 0, mas a refeicao travada PERMANECE")
    void lockSurvivesQueueShrink() throws Exception {
        mvc.perform(put("/attendances/{id}/cancel", attendanceIdA)
                        .header("Authorization", tokenA))
                .andExpect(status().isOk())
                .andDo(print());

        int etaB = waitTime(attendanceIdB, clientUuidB, tokenB);
        // B agora e o primeiro (ninguem a frente, ninguem em atendimento): a projecao daria 0.
        // Como a refeicao foi travada no ingresso, ela continua no ETA dele = exatamente a duracao.
        assertThat(etaB)
                .as("refeicao travada nao sai do ETA mesmo B virando o 1o da fila")
                .isEqualTo(MEAL_MINUTES);
    }

    @Test
    @Order(40)
    @DisplayName("Skip (#41) derruba a refeicao mesmo travada (override) → ETA cai para 0")
    void skipOverridesLock() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profId, mealId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        int etaB = waitTime(attendanceIdB, clientUuidB, tokenB);
        assertThat(etaB)
                .as("o skip derruba a refeicao mesmo travada")
                .isZero();
    }

    @Test
    @Order(50)
    @DisplayName("Unskip restaura a refeicao travada no ETA de B")
    void unskipRestoresLock() throws Exception {
        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}/skip", profId, mealId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        int etaB = waitTime(attendanceIdB, clientUuidB, tokenB);
        assertThat(etaB)
                .as("ao desfazer o skip, a refeicao travada volta ao ETA")
                .isEqualTo(MEAL_MINUTES);
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
