package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.model.CutRequest;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — BUG5 C2 "solicitar corte", fatia 2 (profissional aceita/recusa).
 *
 * accept: nasce um Attendance SCHEDULED na cauda (bypassa o gate C1); refuse: pedido vira REFUSED.
 * So o profissional dono, so PENDING (e nao-expirado no accept).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CutRequestAcceptRefuseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CutRequestRepository cutRequestRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();

    String clientToken, ownerProfToken, professionalId, establishmentId, serviceItemId;
    String anotherProfToken; // PROFESSIONAL de outra loja

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();       // CLOSED por default -> permite solicitar corte
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Prof A");
        var s = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemId = s.id();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        var another = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var anotherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, another.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, another.token(), anotherEstab.id(), "Prof B");
        this.anotherProfToken = another.token();

        log.info("[CutRequestAccept] setup — estab={} prof={}", establishmentId, professionalId);
    }

    @AfterEach
    void clean() {
        cutRequestRepository.deleteAll();     // FK resulting_attendance_id -> apaga o pedido antes
        attendanceRepository.deleteAll();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Cliente cria um pedido de corte (loja fechada) e devolve o id. */
    private String createRequest() throws Exception {
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"]}";
        MvcResult r = mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private MvcResult decide(String id, String verb, String token) throws Exception {
        return mvc.perform(patch("/attendances/cut-requests/{id}/{verb}", id, verb)
                        .header("Authorization", token))
                .andReturn();
    }

    private long queueSize() {
        return attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId)).size();
    }

    // ── Casos ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("aceite -> 200 ACCEPTED + nasce Attendance SCHEDULED na fila")
    void accept_createsScheduledAttendance() throws Exception {
        String id = createRequest();
        assertThat(queueSize()).isZero();

        mvc.perform(patch("/attendances/cut-requests/{id}/accept", id)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.resulting_attendance_id").exists());

        assertThat(queueSize()).isEqualTo(1);
    }

    @Test
    @Order(15)
    @DisplayName("DEC-27: pedido para outro dia (sem vencimento) -> o aceite funciona, sem tratar o nulo como vencido")
    void acceptNoExpiryRequest() throws Exception {
        String tomorrow = com.ospaulos.vupth.domain.shared.BusinessClock.at(
                com.ospaulos.vupth.domain.shared.BusinessClock.today().plusDays(1), java.time.LocalTime.of(10, 0)).toString();
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"],\"desiredTime\":\"" + tomorrow + "\"}";
        MvcResult r = mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");

        mvc.perform(patch("/attendances/cut-requests/{id}/accept", id)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    @Order(20)
    @DisplayName("recusa -> 200 REFUSED + nenhum atendimento criado")
    void refuse_noAttendance() throws Exception {
        String id = createRequest();

        mvc.perform(patch("/attendances/cut-requests/{id}/refuse", id)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUSED"));

        assertThat(queueSize()).isZero();
    }

    @Test
    @Order(30)
    @DisplayName("aceitar duas vezes -> 409 CUT_REQUEST_NOT_PENDING")
    void acceptTwice_conflict() throws Exception {
        String id = createRequest();
        decide(id, "accept", ownerProfToken);
        assertThat(decide(id, "accept", ownerProfToken).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    @Order(40)
    @DisplayName("profissional de outra loja -> 403")
    void notOwner_forbidden() throws Exception {
        String id = createRequest();
        assertThat(decide(id, "accept", anotherProfToken).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Order(50)
    @DisplayName("pedido vencido -> 409 CUT_REQUEST_EXPIRED (aceite defensivo, sem timer)")
    void expired_conflict() throws Exception {
        String id = createRequest();
        // Envelhece o pedido direto no banco (o timer da fatia 3 ainda nao existe).
        CutRequest cut = cutRequestRepository.findById(UUID.fromString(id)).orElseThrow();
        cut.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        cutRequestRepository.save(cut);

        MvcResult res = decide(id, "accept", ownerProfToken);
        assertThat(res.getResponse().getStatus()).isEqualTo(409);
        assertThat(res.getResponse().getContentAsString()).contains("CUT_REQUEST_EXPIRED");
        assertThat(cutRequestRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
                .isEqualTo(CutRequestStatus.EXPIRED);
    }

    @Test
    @Order(60)
    @DisplayName("inexistente -> 404")
    void notFound() throws Exception {
        assertThat(decide(UUID.randomUUID().toString(), "accept", ownerProfToken)
                .getResponse().getStatus()).isEqualTo(404);
    }
}
