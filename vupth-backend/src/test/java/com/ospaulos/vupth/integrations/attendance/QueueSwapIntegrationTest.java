package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.queue.swap.dto.request.SwapRequestCreateRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.queue.swap.SwapRequestStatus;
import com.ospaulos.vupth.domain.queue.swap.repository.QueueSwapRequestRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
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
 * Integration tests — item #42: troca de posicao na fila por consentimento mutuo (cliente<->cliente).
 *
 * O cliente A propoe a troca do seu atendimento com o de B (mesma fila); a troca dos scheduledTime
 * so efetiva quando B aceita (aceiteA && aceiteB). Expira por evento: se um deles sai da fila
 * (atendido/cancelado), as propostas PENDING viram CANCELLED.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QueueSwapIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired QueueSwapRequestRepository swapRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;

    /** Cliente em fila: id do attendance + token do dono + clientId. */
    private record Q(String id, String token, String clientId) {}

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        // Loja "aberta agora" — walk-in do cliente so entra com a loja aberta (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);

        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Cliente novo entra na fila (walk-in), 201. Retorna attendance id + token + clientId. */
    private Q enqueue() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId),
                UUID.fromString(client.id()), serviceItemIds, AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    private MvcResult propose(String token, String proposerId, String targetId) throws Exception {
        var body = new SwapRequestCreateRequest(UUID.fromString(proposerId), UUID.fromString(targetId));
        return mvc.perform(post("/swap-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
    }

    private MvcResult respond(String token, String swapId, boolean accept) throws Exception {
        return mvc.perform(patch("/swap-requests/{id}", swapId)
                        .param("accept", String.valueOf(accept))
                        .header("Authorization", token))
                .andReturn();
    }

    private Attendance reload(String id) {
        return attendanceRepository.findById(UUID.fromString(id)).orElseThrow();
    }

    private int waitingIndexOf(String id) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().toString().equals(id)) return i;
        }
        return -1;
    }

    @AfterEach
    void clearQueue() {
        swapRepository.deleteAll();
        attendanceRepository.deleteAll();
    }

    // ── Propor ────────────────────────────────────────────────────

    @Test @Order(10)
    @DisplayName("Propor troca valida (mesma fila, ambos SCHEDULED) -> 201 PENDING")
    void propose_valid_201() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        MvcResult r = propose(a.token(), a.id(), b.id());
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        assertThat(body.getString("status")).isEqualTo("PENDING");
    }

    @Test @Order(11)
    @DisplayName("Propor com atendimento de outro cliente (nao e o dono) -> 403")
    void propose_notOwner_403() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        // B tenta propor usando o atendimento de A como proposer
        MvcResult r = propose(b.token(), a.id(), b.id());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test @Order(12)
    @DisplayName("Propor duplicado (ja ha PENDING entre o par) -> 409")
    void propose_duplicate_409() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        assertThat(propose(a.token(), a.id(), b.id()).getResponse().getStatus()).isEqualTo(201);
        MvcResult r = propose(a.token(), a.id(), b.id());
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
    }

    @Test @Order(13)
    @DisplayName("Propor com alguem que ja foi atendido (nao SCHEDULED) -> 422")
    void propose_notScheduled_422() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        // inicia o atendimento de B -> IN_PROGRESS
        mvc.perform(patch("/attendances/{id}/start", b.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        MvcResult r = propose(a.token(), a.id(), b.id());
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    // ── Responder ─────────────────────────────────────────────────

    @Test @Order(20)
    @DisplayName("Aceitar -> troca as posicoes (scheduledTime) e marca ACCEPTED")
    void accept_swapsPositions() throws Exception {
        Q a = enqueue(); // indice 0
        Q b = enqueue(); // indice 1
        assertThat(waitingIndexOf(a.id())).isZero();
        assertThat(waitingIndexOf(b.id())).isEqualTo(1);

        String swapId = new JSONObject(propose(a.token(), a.id(), b.id())
                .getResponse().getContentAsString()).getString("id");

        MvcResult r = respond(b.token(), swapId, true);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(new JSONObject(r.getResponse().getContentAsString()).getString("status")).isEqualTo("ACCEPTED");

        // posicoes trocadas: B agora na frente, A atras
        assertThat(waitingIndexOf(b.id())).as("B foi para a frente").isZero();
        assertThat(waitingIndexOf(a.id())).as("A foi para tras").isEqualTo(1);
    }

    @Test @Order(21)
    @DisplayName("Recusar -> REJECTED, nada muda na fila")
    void reject_noChange() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String swapId = new JSONObject(propose(a.token(), a.id(), b.id())
                .getResponse().getContentAsString()).getString("id");

        MvcResult r = respond(b.token(), swapId, false);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(new JSONObject(r.getResponse().getContentAsString()).getString("status")).isEqualTo("REJECTED");
        assertThat(waitingIndexOf(a.id())).isZero();
        assertThat(waitingIndexOf(b.id())).isEqualTo(1);
    }

    @Test @Order(22)
    @DisplayName("Quem responde precisa ser o alvo (B), nao o proponente -> 403")
    void respond_notTarget_403() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String swapId = new JSONObject(propose(a.token(), a.id(), b.id())
                .getResponse().getContentAsString()).getString("id");
        // A (proponente) tenta aceitar a propria proposta
        MvcResult r = respond(a.token(), swapId, true);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test @Order(23)
    @DisplayName("Responder proposta ja respondida -> 422 (nao mais PENDING)")
    void respond_notPending_422() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String swapId = new JSONObject(propose(a.token(), a.id(), b.id())
                .getResponse().getContentAsString()).getString("id");
        assertThat(respond(b.token(), swapId, false).getResponse().getStatus()).isEqualTo(200); // REJECTED
        MvcResult r = respond(b.token(), swapId, true);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    // ── Expiracao por evento ──────────────────────────────────────

    @Test @Order(30)
    @DisplayName("Proponente atendido -> proposta PENDING vira CANCELLED")
    void expire_whenProposerServed() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String swapId = new JSONObject(propose(a.token(), a.id(), b.id())
                .getResponse().getContentAsString()).getString("id");

        // o profissional atende A (sai da fila)
        mvc.perform(patch("/attendances/{id}/start", a.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        var swap = swapRepository.findById(UUID.fromString(swapId)).orElseThrow();
        assertThat(swap.getStatus()).isEqualTo(SwapRequestStatus.CANCELLED);
    }

    @Test @Order(31)
    @DisplayName("Proponente cancela -> proposta PENDING vira CANCELLED")
    void expire_whenProposerCancels() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String swapId = new JSONObject(propose(a.token(), a.id(), b.id())
                .getResponse().getContentAsString()).getString("id");

        mvc.perform(put("/attendances/{id}/cancel", a.id()).header("Authorization", a.token()))
                .andExpect(status().isOk());

        var swap = swapRepository.findById(UUID.fromString(swapId)).orElseThrow();
        assertThat(swap.getStatus()).isEqualTo(SwapRequestStatus.CANCELLED);
    }

    @AfterAll
    void tearDown() {
        swapRepository.deleteAll();
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
