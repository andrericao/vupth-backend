package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
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
 * Integration tests — contador de atrasos (D4, Andre 25/09).
 *
 * <p>Um mesmo cliente e marcado atrasado em cortes diferentes: a marcacao dentro da tolerancia nao
 * conta, as de fora somam entre atendimentos, o 3o atraso vira multa de R$ 5,00 (pendente, cobrada no
 * proximo) e zera o contador; o undo-late desconta. A marcacao em si segue livre (#19).
 *
 * <p>A tolerancia e de minutos reais; o teste a vence escrevendo {@code promised_time} no passado.
 * Nenhum corte e concluido aqui, entao a "chegada da vez" nao entra na conta.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LateCountIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    String clientId, clientToken;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken);
        this.establishmentId = estab.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof Atraso");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
        var service = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(service.id()));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = client.id();
        this.clientToken = client.token();
    }

    @AfterEach
    void leaveQueue() {
        // cada caso comeca com a fila vazia — o contador e do cliente, nao do atendimento
        attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                        UUID.fromString(professionalId), UUID.fromString(establishmentId))
                .forEach(a -> {
                    a.setStatus(AttendanceStatus.CANCELLED);
                    attendanceRepository.save(a);
                });
    }

    // ── helpers ──────────────────────────────────────────────────

    private String enter() throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(clientId),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    /** O cliente "deveria ter chegado" ha 10 min: a marcacao ja passa da tolerancia. */
    private void promisedTenMinutesAgo(String attendanceId) {
        var attendance = attendanceRepository.findById(UUID.fromString(attendanceId)).orElseThrow();
        attendance.setPromisedTime(OffsetDateTime.now().minusMinutes(10));
        attendanceRepository.save(attendance);
    }

    private void markLate(String attendanceId) throws Exception {
        mvc.perform(patch("/attendances/{id}/late", attendanceId)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());
    }

    private JSONObject clientProfile() throws Exception {
        MvcResult r = mvc.perform(get("/clients/{id}", clientId)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString());
    }

    // ── casos ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("Marcado atrasado logo na entrada (antes do prometido + 5 min): cai de posicao, mas nao conta")
    void markWithinTolerance_notCounted() throws Exception {
        String id = enter();

        markLate(id);

        assertThat(attendanceRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
                .as("a marcacao segue livre (#19)").isEqualTo(AttendanceStatus.LATE);
        assertThat(clientProfile().getInt("lateCount")).isZero();
        assertThat(clientProfile().getInt("latesUntilPenalty")).isEqualTo(3);
    }

    @Test
    @Order(20)
    @DisplayName("Depois da tolerancia conta — e o wait-time e o perfil mostram quantos ja tem")
    void markAfterTolerance_counted() throws Exception {
        String id = enter();
        promisedTenMinutesAgo(id);

        markLate(id);

        assertThat(clientProfile().getInt("lateCount")).isEqualTo(1);
        MvcResult wait = mvc.perform(get("/client-queue/{attendanceId}/wait-time", id)
                        .param("clientId", clientId)
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject w = new JSONObject(wait.getResponse().getContentAsString());
        assertThat(w.getInt("lateCount")).isEqualTo(1);
        assertThat(w.getInt("latesUntilPenalty")).isEqualTo(2);
    }

    @Test
    @Order(30)
    @DisplayName("undo-late desconta o atraso que contou")
    void undoLate_discounts() throws Exception {
        String id = enter();
        promisedTenMinutesAgo(id);
        markLate(id);
        assertThat(clientProfile().getInt("lateCount")).isEqualTo(2);

        mvc.perform(patch("/attendances/{id}/undo-late", id)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        assertThat(clientProfile().getInt("lateCount")).isEqualTo(1);
    }

    @Test
    @Order(40)
    @DisplayName("Somando cortes, o 3o atraso vira multa de R$ 5,00 pendente, ele sai da fila e o contador fica em 3")
    void thirdLate_acrossAttendances_penalty() throws Exception {
        String second = enter();
        promisedTenMinutesAgo(second);
        markLate(second); // 2o
        leaveQueue();

        String third = enter();
        promisedTenMinutesAgo(third);
        markLate(third); // 3o -> multa

        assertThat(clientProfile().getInt("lateCount")).as("so zera quando a multa e paga").isEqualTo(3);
        assertThat(attendanceRepository.findById(UUID.fromString(third)).orElseThrow().getStatus())
                .as("3o atraso: sai da fila").isEqualTo(AttendanceStatus.REMOVED);
        MvcResult r = mvc.perform(get("/clients/me/pending-penalties")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andReturn();
        JSONObject pending = new JSONObject(r.getResponse().getContentAsString());
        assertThat(new BigDecimal(pending.get("total").toString())).isEqualByComparingTo("5.00");
        assertThat(pending.getJSONArray("penalties").getJSONObject(0).getString("type")).isEqualTo("LATE_ARRIVAL");
        assertThat(pending.getJSONArray("penalties").getJSONObject(0).getString("originAttendanceId")).isEqualTo(third);
    }

    @Test
    @Order(50)
    @DisplayName("nao-chegou-tres-atrasos: no 3o atraso ele sai da fila (REMOVED), nao vai para o fim; os outros seguem na fila")
    void thirdLate_leavesTheQueue() throws Exception {
        // comeca limpo: o atendimento do caso anterior ja saiu (REMOVED); o contador esta em 3 ate a multa ser paga
        String late = enter();
        String last = null;
        for (int i = 0; i < 4; i++) {
            var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            last = enterAs(other.id(), other.token());
        }

        promisedTenMinutesAgo(late);
        markLate(late); // contador armado em 3: este atraso gera outra multa e a saida

        var waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        assertThat(waiting).extracting(a -> a.getId().toString()).doesNotContain(late).contains(last);
        assertThat(attendanceRepository.findById(UUID.fromString(late)).orElseThrow().getStatus())
                .isEqualTo(AttendanceStatus.REMOVED);
        assertThat(clientProfile().getInt("lateCount")).isEqualTo(3);
    }

    private String enterAs(String id, String token) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(id),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    @AfterAll
    void tearDown() {
        penaltyEventRepository.deleteAll();
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
