package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test — quem sobe por desistencia segue protegido ate aceitar o horario novo (D8, 17/09).
 *
 * <p>A (30 min) na frente de B. A desiste: a vez de B chega agora, mas o horario que vale para o atraso
 * continua o prometido na entrada — marcar B atrasado ja nao conta. Quando B aceita, o horario novo vira
 * o prometido.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EarlierTurnIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    String clientA, tokenA, clientB, tokenB, attendanceA, attendanceB;
    OffsetDateTime promisedAtEntry;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken);
        this.establishmentId = estab.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof Adianta");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
        var service = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(service.id()));

        var a = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientA = a.id();
        this.tokenA = a.token();
        var b = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientB = b.id();
        this.tokenB = b.token();
    }

    private String enter(String clientId, String token, int inMinutes) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(clientId),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(inMinutes), 30,
                OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private JSONObject waitTimeOfB() throws Exception {
        MvcResult r = mvc.perform(get("/client-queue/{attendanceId}/wait-time", attendanceB)
                        .param("clientId", clientB)
                        .header("Authorization", tokenB))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString());
    }

    @Test
    @Order(10)
    @DisplayName("Na entrada, B (atras de A) recebe o horario prometido = entrada + espera")
    void entry_promisedTime() throws Exception {
        this.attendanceA = enter(clientA, tokenA, 0);
        this.attendanceB = enter(clientB, tokenB, 1);

        JSONObject w = waitTimeOfB();
        this.promisedAtEntry = OffsetDateTime.parse(w.getString("promisedTime"));
        assertThat(promisedAtEntry).as("A (30 min) a frente")
                .isAfter(OffsetDateTime.now().plusMinutes(25));
    }

    @Test
    @Order(20)
    @DisplayName("A desiste: a vez de B chega agora, mas o horario que vale para o atraso nao muda")
    void withdrawal_keepsPromise() throws Exception {
        mvc.perform(put("/attendances/{id}/cancel", attendanceA)
                        .header("Authorization", tokenA))
                .andExpect(status().isOk());

        JSONObject w = waitTimeOfB();
        assertThat(w.getInt("estimatedWaitingTime")).as("B virou o primeiro").isZero();
        assertThat(OffsetDateTime.parse(w.getString("promisedTime"))).isEqualTo(promisedAtEntry);
    }

    @Test
    @Order(30)
    @DisplayName("Protegido: marcar B atrasado agora nao conta (a promessa ainda nao chegou)")
    void protectedFromLate() throws Exception {
        mvc.perform(patch("/attendances/{id}/late", attendanceB)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        assertThat(waitTimeOfB().getInt("lateCount")).isZero();

        mvc.perform(patch("/attendances/{id}/undo-late", attendanceB)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(40)
    @DisplayName("B aceita o horario novo: ele vira o prometido (e o atraso volta a contar dele)")
    void accept_newPromise() throws Exception {
        MvcResult r = mvc.perform(patch("/attendances/{id}/accept-earlier-turn", attendanceB)
                        .header("Authorization", tokenB))
                .andExpect(status().isOk())
                .andReturn();
        OffsetDateTime accepted = OffsetDateTime.parse(
                new JSONObject(r.getResponse().getContentAsString()).getString("promisedTime"));

        assertThat(accepted).isBefore(promisedAtEntry.minusMinutes(20));
        // o banco guarda microssegundos; a resposta do aceite ainda tem os nanos do relogio
        assertThat(OffsetDateTime.parse(waitTimeOfB().getString("promisedTime")).toInstant())
                .isCloseTo(accepted.toInstant(), org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MILLIS));
    }

    @Test
    @Order(50)
    @DisplayName("Aceitar pelo atendimento de outro cliente: 403")
    void accept_notOwner_forbidden() throws Exception {
        mvc.perform(patch("/attendances/{id}/accept-earlier-turn", attendanceB)
                        .header("Authorization", tokenA))
                .andExpect(status().isForbidden());
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
