package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.queue.offer.dto.request.SwapOfferCreateRequest;
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
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
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
 * Integration tests — oferta ABERTA de troca de posicao (Client C13 / Partner S11), fatia 2: o
 * solicitante esta FORA da fila (posicao implicita = fim, por isso so {@code EARLIER}). Ele escolhe
 * profissional + servicos; ao ser aceita, quem aceitou CEDE a vaga e vai pro fim, e o solicitante
 * nasce naquela vaga (o atendimento novo vem em {@code resultingAttendanceId}). Continua 1-por-1 e
 * nunca gera multa. A fatia 1 (solicitante na fila) tem classe propria.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwapOfferOutOfQueueIntegrationTest {

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

    /** Servico de OUTRO profissional (outra loja) — viola a invariante servico<->profissional. */
    List<UUID> foreignServiceItemIds;

    /** Cliente em fila: id do attendance + token do dono + clientId. */
    private record Q(String id, String token, String clientId) {}

    /** Cliente registrado que NAO esta na fila (o solicitante da fatia 2). */
    private record C(String token, String clientId) {}

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");

        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Segunda loja/profissional so para ter um serviceItem que NAO e do profissional-alvo.
        var otherProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, otherProf.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, otherProf.token(), otherEstab.id(), "Other Professional");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);
        TestUserUtils.startShift(professionalRepository, otherEstab.id());
        var s2 = TestUserUtils.createServiceItem(mvc, mapper, otherEstab.id(), otherProf.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte alheio", BigDecimal.valueOf(30));
        this.foreignServiceItemIds = List.of(UUID.fromString(s2.id()));
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Cliente novo entra na fila (walk-in), 201. A ordem de chamada define a posicao (0, 1, 2...). */
    private Q enqueue() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        return enqueue(new C(client.token(), client.id()));
    }

    /** Coloca na fila um cliente que ja existe (usado para "publicou fora e depois entrou sozinho"). */
    private Q enqueue(C client) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId),
                UUID.fromString(client.clientId()), serviceItemIds, AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.clientId());
    }

    /** Cliente registrado que NAO entra na fila. */
    private C outsider() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        return new C(user.token(), user.id());
    }

    private MvcResult createOutOfQueue(String token, List<UUID> items, SwapOfferDirection dir) throws Exception {
        var body = new SwapOfferCreateRequest(null, UUID.fromString(professionalId), items, dir);
        return mvc.perform(post("/swap-offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(body)))
                .andReturn();
    }

    private String createOutOfQueueId(String token) throws Exception {
        MvcResult r = createOutOfQueue(token, serviceItemIds, SwapOfferDirection.EARLIER);
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

    /** Indice na fila viva, ou -1 se nao esta nela. */
    private int waitingIndexOf(String attendanceId) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().toString().equals(attendanceId)) return i;
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
    @DisplayName("Publicar de FORA da fila (professionalId + servicos) -> 201 OPEN sem requesterAttendanceId")
    void create_valid_201() throws Exception {
        enqueue();
        C r = outsider();

        MvcResult res = createOutOfQueue(r.token(), serviceItemIds, SwapOfferDirection.EARLIER);
        assertThat(res.getResponse().getStatus()).isEqualTo(201);

        JSONObject body = new JSONObject(res.getResponse().getContentAsString());
        assertThat(body.getString("status")).isEqualTo("OPEN");
        assertThat(body.getString("direction")).isEqualTo("EARLIER");
        assertThat(body.isNull("requesterAttendanceId")).as("fora da fila nao tem atendimento").isTrue();
        assertThat(body.isNull("resultingAttendanceId")).as("so nasce no aceite").isTrue();
        assertThat(body.getJSONArray("serviceItemIds").length()).isEqualTo(1);
        assertThat(body.getString("requesterClientId")).isEqualTo(r.clientId());
    }

    @Test @Order(11)
    @DisplayName("Publicar de fora com direction LATER -> 422 (fora da fila ja e o fim)")
    void create_later_422() throws Exception {
        enqueue();
        C r = outsider();
        MvcResult res = createOutOfQueue(r.token(), serviceItemIds, SwapOfferDirection.LATER);
        assertThat(res.getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(12)
    @DisplayName("Publicar de fora sem servicos -> 404 SERVICE_ITEM_NOT_FOUND")
    void create_noServices_404() throws Exception {
        enqueue();
        C r = outsider();
        MvcResult res = createOutOfQueue(r.token(), List.of(), SwapOfferDirection.EARLIER);
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
    }

    @Test @Order(13)
    @DisplayName("Publicar de fora com servico de OUTRO profissional -> 422 (invariante BUG8c)")
    void create_foreignService_422() throws Exception {
        enqueue();
        C r = outsider();
        MvcResult res = createOutOfQueue(r.token(), foreignServiceItemIds, SwapOfferDirection.EARLIER);
        assertThat(res.getResponse().getStatus()).isEqualTo(422);
        assertThat(res.getResponse().getContentAsString()).contains("SERVICE_ITEM_NOT_OF_PROFESSIONAL");
    }

    @Test @Order(14)
    @DisplayName("Publicar de fora sem token -> 401")
    void create_noToken_401() throws Exception {
        var body = new SwapOfferCreateRequest(null, UUID.fromString(professionalId),
                serviceItemIds, SwapOfferDirection.EARLIER);
        mvc.perform(post("/swap-offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    // ── Listar (todos na fila estao a frente do "fim") ────────────

    @Test @Order(20)
    @DisplayName("Oferta de fora e visivel para TODOS os que estao na fila; quem esta fora nao ve nada")
    void list_visibleToEveryoneInQueue() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        assertThat(listContains(listAcceptable(a.token()), offer)).as("1o da fila ve").isTrue();
        assertThat(listContains(listAcceptable(b.token()), offer)).as("ultimo da fila ve").isTrue();
        assertThat(listAcceptable(r.token()).length()).as("o proprio solicitante (fora da fila) nao ve").isZero();
    }

    // ── Aceitar ───────────────────────────────────────────────────

    @Test @Order(30)
    @DisplayName("Aceitar: solicitante nasce na vaga cedida, quem aceitou vai pro FIM, avisa os DOIS")
    void accept_requesterTakesSlot_accepterGoesLast() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1 — vai aceitar e ceder a vaga
        Q c = enqueue(); // idx 2
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        Mockito.clearInvocations(pushPublisher);
        MvcResult res = accept(b.token(), offer);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        JSONObject body = new JSONObject(res.getResponse().getContentAsString());
        assertThat(body.getString("status")).isEqualTo("ACCEPTED");
        String created = body.getString("resultingAttendanceId");
        assertThat(created).isNotBlank();

        // a segue 1o; o solicitante assumiu a vaga do b (idx 1); c sobe uma; b foi pro fim.
        assertThat(waitingIndexOf(a.id())).as("quem nao participou nao se move").isZero();
        assertThat(waitingIndexOf(created)).as("solicitante na vaga cedida").isEqualTo(1);
        assertThat(waitingIndexOf(c.id())).as("quem estava atras sobe uma").isEqualTo(2);
        assertThat(waitingIndexOf(b.id())).as("quem aceitou foi pro fim").isEqualTo(3);

        // O atendimento novo e do solicitante e ja nasce aguardando.
        Attendance novo = attendanceRepository.findById(UUID.fromString(created)).orElseThrow();
        assertThat(novo.getClient().getId().toString()).isEqualTo(r.clientId());
        assertThat(novo.getStatus()).isEqualTo(AttendanceStatus.SCHEDULED);

        assertThat(pushedTypesFor(UUID.fromString(r.clientId())))
                .as("solicitante avisado").contains("SWAP_OFFER_ACCEPTED");
        assertThat(pushedTypesFor(UUID.fromString(b.clientId())))
                .as("quem aceitou avisado").contains("SWAP_OFFER_ACCEPTED");
    }

    @Test @Order(31)
    @DisplayName("Aceitar sendo o ULTIMO da fila -> solicitante fica na frente de quem aceitou")
    void accept_byLastInQueue() throws Exception {
        Q a = enqueue(); // idx 0
        Q b = enqueue(); // idx 1 — ultimo, aceita
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        MvcResult res = accept(b.token(), offer);
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String created = new JSONObject(res.getResponse().getContentAsString()).getString("resultingAttendanceId");

        assertThat(waitingIndexOf(a.id())).isZero();
        assertThat(waitingIndexOf(created)).as("assumiu a vaga do ultimo").isEqualTo(1);
        assertThat(waitingIndexOf(b.id())).as("quem aceitou ficou atras dele").isEqualTo(2);
    }

    @Test @Order(32)
    @DisplayName("Aceitar quem nao esta na fila -> 422")
    void accept_callerOutOfQueue_422() throws Exception {
        enqueue();
        C r = outsider();
        C outro = outsider();
        String offer = createOutOfQueueId(r.token());

        assertThat(accept(outro.token(), offer).getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(33)
    @DisplayName("Aceitar a propria oferta -> 422")
    void accept_ownOffer_422() throws Exception {
        enqueue();
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        assertThat(accept(r.token(), offer).getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(34)
    @DisplayName("Aceitar oferta ja consumida -> 422")
    void accept_alreadyAccepted_422() throws Exception {
        Q a = enqueue();
        Q b = enqueue();
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        assertThat(accept(b.token(), offer).getResponse().getStatus()).isEqualTo(200);
        assertThat(accept(a.token(), offer).getResponse().getStatus()).isEqualTo(422);
    }

    @Test @Order(35)
    @DisplayName("Solicitante entrou na fila sozinho depois de publicar -> oferta some da lista e o aceite da 422")
    void accept_requesterJoinedQueueMeanwhile_422() throws Exception {
        Q a = enqueue();
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        // A posicao implicita (fim) deixou de valer — a oferta virou obsoleta.
        enqueue(r);

        assertThat(listContains(listAcceptable(a.token()), offer)).as("some da lista").isFalse();
        assertThat(accept(a.token(), offer).getResponse().getStatus()).isEqualTo(422);
    }

    // ── Cancelar ──────────────────────────────────────────────────

    @Test @Order(40)
    @DisplayName("Solicitante cancela a propria oferta de fora -> 204 e some do /me")
    void cancel_ownOffer_204() throws Exception {
        Q a = enqueue();
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        mvc.perform(delete("/swap-offers/{id}", offer).header("Authorization", r.token()))
                .andExpect(status().isNoContent());

        assertThat(offerStatus(offer)).isEqualTo(SwapOfferStatus.CANCELLED);

        MvcResult mine = mvc.perform(get("/swap-offers/me").header("Authorization", r.token())).andReturn();
        assertThat(new JSONArray(mine.getResponse().getContentAsString()).length()).isZero();
        assertThat(listContains(listAcceptable(a.token()), offer)).as("some da lista dos outros").isFalse();
    }

    @Test @Order(41)
    @DisplayName("Cancelar oferta de outro cliente -> 403")
    void cancel_notOwner_403() throws Exception {
        Q a = enqueue();
        C r = outsider();
        String offer = createOutOfQueueId(r.token());

        mvc.perform(delete("/swap-offers/{id}", offer).header("Authorization", a.token()))
                .andExpect(status().isForbidden());
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
