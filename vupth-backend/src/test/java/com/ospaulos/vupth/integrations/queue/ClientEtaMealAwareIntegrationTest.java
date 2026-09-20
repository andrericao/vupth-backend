package com.ospaulos.vupth.integrations.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
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
 * Integration test — ETA client-facing meal-aware (item #37 pre-requisito) + efeito do skip
 * (#41) no ETA do cliente.
 *
 * Antes desta mudanca, `estimated_wait_minutes` do snapshot da fila (professionals-with-queue)
 * era soma simples e IGNORAVA refeicoes; agora passa pelo QueueUtils (meal-aware).
 *
 * Estrategia deterministica (independe da hora do relogio): cria uma refeicao recorrente com
 * horario MUITO no passado (00:01) — ela sempre e projetada para "agora" e sempre conta no ETA
 * enquanto houver >=1 atendimento na fila. A asercao e RELATIVA: ETA(com refeicao) -
 * ETA(apos skip) == duracao da refeicao. Isola a contribuicao da refeicao sem depender da
 * duracao do atendimento nem da hora.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientEtaMealAwareIntegrationTest {

    private static final int MEAL_MINUTES = 25;

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository intervalRepository;

    private final Faker faker = new Faker();

    String profToken, clientToken;
    UUID profId, establishmentUuid, clientUuid;
    List<UUID> serviceItemIds;
    UUID mealId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = UUID.fromString(owner.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentUuid = UUID.fromString(estab.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, estab.id());
        this.profToken = relogin(owner.email(), owner.password());

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Loja "aberta agora" — walk-in do cliente so entra com a loja aberta (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, estab.id());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientUuid = UUID.fromString(client.id());
        this.clientToken = client.token();
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

    /** ETA estimado do (unico) profissional no snapshot client-facing da fila. */
    private int estimatedWait() throws Exception {
        MvcResult res = mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentUuid))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject body = new JSONObject(res.getResponse().getContentAsString());
        return body.getJSONArray("professionals").getJSONObject(0).getInt("estimated_wait_minutes");
    }

    @Test
    @Order(10)
    @DisplayName("Setup: refeicao recorrente (sempre projetada p/ agora) + 1 cliente na fila")
    void setupMealAndQueue() throws Exception {
        // Remove as refeicoes semeadas (almoco 12:00 / janta 18:00) para isolar UMA refeicao
        // controlada — sem elas, a refeicao 00:01 nao "some na proxima" e sempre conta (disappearAt
        // = fim do dia). Torna a asercao deterministica independente da hora do relogio.
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

        AttendanceRequest request = new AttendanceRequest(
                profId, establishmentUuid, clientUuid, serviceItemIds,
                AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(30), 30, OffsetDateTime.now());
        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(20)
    @DisplayName("ETA client-facing inclui a refeicao; skip (#41) a remove — diferenca = duracao")
    void clientEta_isMealAware_andSkipRemovesIt() throws Exception {
        int withMeal = estimatedWait();

        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/skip", profId, mealId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andDo(print());

        int afterSkip = estimatedWait();

        assertThat(withMeal)
                .as("ETA com refeicao deve ser maior que apos o skip")
                .isGreaterThan(afterSkip);
        assertThat(withMeal - afterSkip)
                .as("a diferenca deve ser exatamente a duracao da refeicao")
                .isEqualTo(MEAL_MINUTES);
    }

    @Test
    @Order(30)
    @DisplayName("Unskip restaura a refeicao no ETA do cliente")
    void unskip_restoresMealInEta() throws Exception {
        int skipped = estimatedWait();

        mvc.perform(delete("/api/v1/professionals/{pid}/intervals/{iid}/skip", profId, mealId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        int restored = estimatedWait();
        assertThat(restored - skipped)
                .as("ao desfazer o skip, a refeicao volta a somar a duracao")
                .isEqualTo(MEAL_MINUTES);
    }

    @AfterAll
    void tearDown() {
        userRepository.deleteAll();
    }
}
