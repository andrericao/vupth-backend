package com.ospaulos.vupth.integrations.interval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
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
 * Integration tests — "encerrar ao atender" (spec item #11e).
 *
 * Quando o profissional inicia um atendimento (PATCH /attendances/{id}/start), a
 * refeicao que ele iniciou manualmente e ainda esta em curso e encerrada
 * automaticamente (finishedEarly, actualEndTime, autoFinishReason=ATTEND_NEXT_CLIENT).
 * Refeicoes apenas previstas (nunca iniciadas) nao sao afetadas.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntervalFinishOnAttendIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    String profToken, clientToken;
    UUID profId, clientUuid, establishmentUuid, lunchId, dinnerId;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        // Owner-professional (solo shop) — seed de almoco (12:00) + janta (18:00)
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profId = UUID.fromString(owner.id());
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentUuid = UUID.fromString(estab.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());
        this.profToken = relogin(owner.email(), owner.password());

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Loja "aberta agora" — walk-in do cliente so entra com a loja aberta (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, estab.id());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientUuid = UUID.fromString(client.id());
        this.clientToken = client.token();

        this.lunchId = mealId(12);
        this.dinnerId = mealId(18);
    }

    private UUID mealId(int hour) {
        return intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(profId))
                .filter(i -> i.getFixedStartTime() != null && i.getFixedStartTime().getHour() == hour)
                .map(ProfessionalInterval::getId)
                .findFirst().orElseThrow();
    }

    private String relogin(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");
    }

    @Test
    @Order(10)
    @DisplayName("Inicia o almoco (PATCH /start) — fica ativo")
    void startLunch() throws Exception {
        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", profId, lunchId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(intervalRepository.findById(lunchId).orElseThrow().getIsActive()).isTrue();
    }

    @Test
    @Order(20)
    @DisplayName("Iniciar atendimento durante o almoco encerra o almoco (ATTEND_NEXT_CLIENT)")
    void startAttendance_finishesActiveLunch() throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                profId, establishmentUuid, clientUuid, serviceItemIds,
                AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(30), 30, OffsetDateTime.now());

        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        String attendanceId = new JSONObject(created.getResponse().getContentAsString()).getString("id");

        mvc.perform(patch("/attendances/{id}/start", attendanceId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andDo(print());

        ProfessionalInterval lunch = intervalRepository.findById(lunchId).orElseThrow();
        assertThat(lunch.getIsActive()).isFalse();
        assertThat(lunch.getActualEndTime()).isNotNull();
        assertThat(lunch.getFinishedEarly()).isTrue();
        assertThat(lunch.getAutoFinishReason()).isEqualTo("ATTEND_NEXT_CLIENT");
    }

    @Test
    @Order(21)
    @DisplayName("A janta (apenas prevista, nunca iniciada) nao e afetada")
    void dinnerNotStarted_isUntouched() {
        ProfessionalInterval dinner = intervalRepository.findById(dinnerId).orElseThrow();
        assertThat(dinner.getIsActive()).isFalse();
        assertThat(dinner.getActualStartTime()).isNull();
        assertThat(dinner.getActualEndTime()).isNull();
    }

    @AfterAll
    void tearDown() {
        intervalRepository.deleteAll();
        userRepository.deleteAll();
    }
}
