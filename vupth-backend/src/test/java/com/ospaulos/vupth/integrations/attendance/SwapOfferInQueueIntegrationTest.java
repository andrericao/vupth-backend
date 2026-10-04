package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.queue.offer.dto.request.SwapOfferCreateRequest;
import com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferDirection;
import com.ospaulos.vupth.domain.queue.offer.SwapOfferStatus;
import com.ospaulos.vupth.domain.queue.offer.repository.SwapOfferRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — oferta ABERTA de troca de posicao (Client C13 / Partner S11), fatia 1: o
 * solicitante JA esta na fila. Publica uma oferta ({@code EARLIER}/{@code LATER}); qualquer cliente
 * elegivel (por posicao/direcao na mesma fila) aceita -> troca 1-por-1 dos scheduledTime. Nunca multa.
 * Expira por evento (solicitante atendido/cancelado). A oferta de FORA da fila (fatia 2) tem classe
 * propria: {@code SwapOfferOutOfQueueIntegrationTest}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwapOfferInQueueIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired SwapOfferRepository swapOfferRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    /** Mockado para conferir que o accept avisa os DOIS lados (o WS pessoal segue o mesmo caminho). */
    @MockBean PushNotificationPublisher pushPublisher;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    List<UUID> longServiceItemIds;
    List<UUID> fortyMinuteServiceItemIds;

    /** Cliente em fila: id do attendance + token do dono + clientId. */
    private record Q(String id, String token, String clientId) {}

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
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

        // D14: um servico longo (2h, incompativel com o de 30) e um de 40 (no limite de 10 min)
        this.longServiceItemIds = List.of(serviceItemWithMinutes("Progressiva", 120));
        this.fortyMinuteServiceItemIds = List.of(serviceItemWithMinutes("Corte e barba", 40));
    }

    private UUID serviceItemWithMinutes(String name, int minutes) throws Exception {
        MvcResult r = mvc.perform(post("/establishment/" + establishmentId + "/service-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content(mapper.writeValueAsString(new ServiceItemRequest(
                                UUID.fromString(establishmentId), ServiceItemType.CORTE_TESOURA, name,
                                BigDecimal.valueOf(50), minutes, null, null))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(new JSONObject(r.getResponse().getContentAsString()).getString("id"));
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Cliente novo entra na fila (walk-in), 201. A ordem de chamada define a posicao (0, 1, 2...). */
    private Q enqueue() throws Exception {
        return enqueue(serviceItemIds);
    }

    private Q enqueue(List<UUID> services) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId),
                UUID.fromString(client.id()), services, AttendanceStatus.SCHEDULED,
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

    private MvcResult createInQueue(String token, String attendanceId, SwapOfferDirection dir) throws Exception {
        var body = new SwapOfferCreateRequest(UUID.fromString(attendanceId), null, null, dir);
        return mvc.perform(post("/swap-offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
    }

    private String createInQueueId(String token, String attendanceId, SwapOfferDirection dir) throws Exception {
        MvcResult r = createInQueue(token, attendanceId, dir);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private JSONArray listAcceptable(String token) throws Exception {
        MvcResult r = mvc.perform(get("/swap-offers")
                        .param("professionalId", professionalId)
                        .header("Authorization", token))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new JSONArray(r.getResponse().getContentAsString());
    }

    private boolean listContains(JSONArray arr, String offerId) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            if (arr.getJSONObject(i).getString("id").equals(offerId)) return true;
        }
        return false;
    }

    private MvcResult accept(String token, String offerId) throws Exception {
        return mvc.perform(patch("/swap-offers/{id}/accept", offerId).header("Authorization", token))
                .andReturn();
    }

    private int waitingIndexOf(String id) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().toString().equals(id)) return i;
        }
        return -1;
    }

    private SwapOfferStatus offerStatus(String id) {
        return swapOfferRepository.findById(UUID.fromString(id)).orElseThrow().getStatus();
    }

    /** Tipos de push entregues a este cliente desde o ultimo {@code clearInvocations}. */
    private List<String> pushedTypesFor(UUID clientId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(pushPublisher, Mockito.atLeastOnce()).notifyUser(
                Mockito.eq(clientId), Mockito.anyString(), Mockito.anyString(), data.capture());
        return data.getAllValues().stream().map(m -> String.valueOf(m.get("type"))).toList();
    }

    @AfterEach
    void clearQueue() {
        swapOfferRepository.deleteAll();
        attendanceRepository.deleteAll();
    }

    // ── Publicar ──────────────────────────────────────────────────

    @Test @Order(10)
    @DisplayName("Publicar oferta na fila (dono, SCHEDULED) -> 201 OPEN")
    void create_valid_201() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        MvcResult r = createInQueue(b.token(), b.id(), SwapOfferDirection.EARLIER);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        assertThat(body.getString("status")).isEqualTo("OPEN");
        assertThat(body.getString("direction")).isEqualTo("EARLIER");
        assertThat(body.getString("requesterAttendanceId")).isEqualTo(b.id());
    }

    @Test @Order(11)
    @DisplayName("Publicar oferta para atendimento de outro cliente -> 403")
    void create_notOwner_403() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        MvcResult r = createInQueue(b.token(), a.id(), SwapOfferDirection.EARLIER);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    @Test @Order(12)
    @DisplayName("Publicar oferta com atendimento ja iniciado (nao SCHEDULED) -> 422")
    void create_notScheduled_422() throws Exception {
        Q a = enqueue();
        mvc.perform(patch("/attendances/{id}/start", a.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        MvcResult r = createInQueue(a.token(), a.id(), SwapOfferDirection.EARLIER);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(13)
    @DisplayName("Quem JA esta na fila publicando como se estivesse fora dela -> 422 (use o attendanceId)")
    void create_outOfQueueWhileInQueue_422() throws Exception {
        Q a = enqueue();
        // A posicao implicita da oferta de fora e o FIM da fila — mentirosa para quem ja tem lugar.
        // A oferta correta para ele e a de dentro ({attendanceId, direction}); ver a fatia 2.
        var body = new SwapOfferCreateRequest(null, UUID.fromString(professionalId),
                serviceItemIds, SwapOfferDirection.EARLIER);
        MvcResult r = mvc.perform(post("/swap-offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", a.token())
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    // ── Listar (elegibilidade por posicao/direcao) ────────────────

    @Test @Order(20)
    @DisplayName("EARLIER: visivel para quem esta a FRENTE do solicitante, nao para quem esta atras")
    void list_earlier_visibleToThoseAhead() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1
        Q c = enqueue(); // idx 2
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);

        assertThat(listContains(listAcceptable(a.token()), offer)).as("A (frente) ve").isTrue();
        assertThat(listContains(listAcceptable(c.token()), offer)).as("C (atras) nao ve").isFalse();
        assertThat(listContains(listAcceptable(b.token()), offer)).as("o proprio nao ve").isFalse();
    }

    @Test @Order(21)
    @DisplayName("LATER: visivel para quem esta ATRAS do solicitante, nao para quem esta a frente")
    void list_later_visibleToThoseBehind() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1
        Q c = enqueue(); // idx 2
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.LATER);

        assertThat(listContains(listAcceptable(c.token()), offer)).as("C (atras) ve").isTrue();
        assertThat(listContains(listAcceptable(a.token()), offer)).as("A (frente) nao ve").isFalse();
    }

    // ── Aceitar ───────────────────────────────────────────────────

    @Test @Order(30)
    @DisplayName("Aceitar EARLIER (por quem esta a frente) -> troca posicoes, marca ACCEPTED e avisa os DOIS")
    void accept_earlier_swapsPositions() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1
        assertThat(waitingIndexOf(a.id())).isZero();
        assertThat(waitingIndexOf(b.id())).isEqualTo(1);

        Mockito.clearInvocations(pushPublisher);

        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        MvcResult r = accept(a.token(), offer);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(new JSONObject(r.getResponse().getContentAsString()).getString("status")).isEqualTo("ACCEPTED");

        assertThat(waitingIndexOf(b.id())).as("B foi para a frente").isZero();
        assertThat(waitingIndexOf(a.id())).as("A foi para tras").isEqualTo(1);

        // Contrato do handoff Front-Client (secao 7): o accept notifica os DOIS lados.
        assertThat(pushedTypesFor(UUID.fromString(b.clientId())))
                .as("solicitante avisado").contains("SWAP_OFFER_ACCEPTED");
        assertThat(pushedTypesFor(UUID.fromString(a.clientId())))
                .as("quem aceitou avisado").contains("SWAP_OFFER_ACCEPTED");
    }

    @Test @Order(31)
    @DisplayName("Aceitar a propria oferta -> 422")
    void accept_ownOffer_422() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        MvcResult r = accept(b.token(), offer);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(32)
    @DisplayName("Aceitar sem ser elegivel (atras, numa oferta EARLIER) -> 422")
    void accept_notEligible_422() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1
        Q c = enqueue(); // idx 2
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        // C esta ATRAS de B -> nao pode aceitar uma oferta EARLIER de B
        MvcResult r = accept(c.token(), offer);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(33)
    @DisplayName("Aceitar sem estar na fila do profissional -> 422")
    void accept_callerNotInQueue_422() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        var outsider = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult r = accept(outsider.token(), offer);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(34)
    @DisplayName("Aceitar oferta ja consumida -> 422 (nao mais OPEN)")
    void accept_notOpen_422() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1
        Q c = enqueue(); // idx 2 (fica na fila, apenas garante que ha 3)
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        assertThat(accept(a.token(), offer).getResponse().getStatus()).isEqualTo(200);
        // segunda tentativa (por A de novo) -> ja ACCEPTED
        MvcResult r = accept(a.token(), offer);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
    }

    // ── Cancelar / listar minhas ──────────────────────────────────

    // ── D14: duracao compativel (ate 10 min de diferenca) ─────────

    @Test @Order(35)
    @DisplayName("D14: 30 min x 2h -> a oferta nao aparece na lista e o aceite da 422 QUEUE_SWAP_DURATION_INCOMPATIBLE")
    void accept_incompatibleDuration_422() throws Exception {
        Q a = enqueue();                     // idx 0, 30 min
        Q b = enqueue(longServiceItemIds);   // idx 1, 120 min

        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        assertThat(listContains(listAcceptable(a.token()), offer))
                .as("oferta incompativel fora da lista").isFalse();

        MvcResult r = accept(a.token(), offer);
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(new JSONObject(r.getResponse().getContentAsString()).getString("code"))
                .isEqualTo("QUEUE_SWAP_DURATION_INCOMPATIBLE");

        assertThat(waitingIndexOf(a.id())).as("a fila nao muda").isZero();
        assertThat(waitingIndexOf(b.id())).isEqualTo(1);
        assertThat(swapOfferRepository.findById(UUID.fromString(offer)).orElseThrow().getStatus())
                .as("a oferta continua aberta").isEqualTo(SwapOfferStatus.OPEN);
    }

    @Test @Order(36)
    @DisplayName("D14: 30 min x 40 min (10 de diferenca) -> aparece na lista e troca")
    void accept_tenMinuteGap_swaps() throws Exception {
        Q a = enqueue();                            // idx 0, 30 min
        Q b = enqueue(fortyMinuteServiceItemIds);   // idx 1, 40 min

        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        assertThat(listContains(listAcceptable(a.token()), offer)).isTrue();

        assertThat(accept(a.token(), offer).getResponse().getStatus()).isEqualTo(200);
        assertThat(waitingIndexOf(b.id())).isZero();
    }

    @Test @Order(40)
    @DisplayName("Cancelar a propria oferta -> 204 CANCELLED; some do /me")
    void cancel_byRequester_204() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);

        // aparece em /me
        MvcResult mine = mvc.perform(get("/swap-offers/me").header("Authorization", b.token())).andReturn();
        assertThat(new JSONArray(mine.getResponse().getContentAsString()).length()).isEqualTo(1);

        mvc.perform(delete("/swap-offers/{id}", offer).header("Authorization", b.token()))
                .andExpect(status().isNoContent());
        assertThat(offerStatus(offer)).isEqualTo(SwapOfferStatus.CANCELLED);

        MvcResult mineAfter = mvc.perform(get("/swap-offers/me").header("Authorization", b.token())).andReturn();
        assertThat(new JSONArray(mineAfter.getResponse().getContentAsString()).length()).isZero();
    }

    @Test @Order(41)
    @DisplayName("Cancelar oferta de outro cliente -> 403")
    void cancel_notOwner_403() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);
        mvc.perform(delete("/swap-offers/{id}", offer).header("Authorization", a.token()))
                .andExpect(status().isForbidden());
    }

    // ── Expiracao por evento ──────────────────────────────────────

    @Test @Order(50)
    @DisplayName("Solicitante atendido (start) -> oferta OPEN vira EXPIRED")
    void expire_whenRequesterServed() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);

        mvc.perform(patch("/attendances/{id}/start", b.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        assertThat(offerStatus(offer)).isEqualTo(SwapOfferStatus.EXPIRED);
    }

    @Test @Order(51)
    @DisplayName("Solicitante cancela o atendimento -> oferta OPEN vira EXPIRED")
    void expire_whenRequesterCancels() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        String offer = createInQueueId(b.token(), b.id(), SwapOfferDirection.EARLIER);

        mvc.perform(put("/attendances/{id}/cancel", b.id()).header("Authorization", b.token()))
                .andExpect(status().isOk());
        assertThat(offerStatus(offer)).isEqualTo(SwapOfferStatus.EXPIRED);
    }

    @AfterAll
    void tearDown() {
        swapOfferRepository.deleteAll();
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
