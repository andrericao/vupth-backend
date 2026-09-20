package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRatingRequest;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRatingRepository;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
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
 * Integration tests — item #6 (rating / avaliacao de atendimentos).
 *
 * Regra: apos um atendimento FINISHED, o cliente dono avalia com 1-5 estrelas + comentario
 * opcional via {@code POST /attendances/{id}/rating}. Atribuicao (Andre): 1 servico -> nota vai
 * pro profissional E pro ServiceItem; 2+ servicos -> so pro profissional (serviceItemId null).
 * 1 voto por atendimento (409 no 2o), so FINISHED (senao 400), so o dono (senao 403).
 *
 * Os agregados (rating_sum/rating_count) acumulam no mesmo profissional/servico ao longo dos
 * testes -> as asserts usam DELTA (antes/depois), independentes da ordem.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceRatingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired AttendanceRatingRepository attendanceRatingRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    UUID service1Id, service2Id;

    /** Cliente em fila: id do attendance + token + clientId. */
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
        var s2 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.BARBA, "Barba", BigDecimal.valueOf(20));
        this.service1Id = UUID.fromString(s1.id());
        this.service2Id = UUID.fromString(s2.id());

        log.info("[Rating] setup — estab={} prof={} s1={} s2={}", establishmentId, professionalId, service1Id, service2Id);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Cria um walk-in (cliente registrado novo) com os servicos dados, 201, e devolve o handle. */
    private Q createWalkin(List<UUID> serviceItemIds) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(client.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(),
                30,
                OffsetDateTime.now(),
                null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("walk-in criado").isEqualTo(201);
        String id = new JSONObject(r.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    /** Leva o atendimento a FINISHED: start + finish pelo profissional dono. */
    private void finish(String attendanceId) throws Exception {
        mvc.perform(patch("/attendances/{id}/start", attendanceId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", attendanceId)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        assertThat(attendanceRepository.findById(UUID.fromString(attendanceId)).orElseThrow().getStatus())
                .isEqualTo(AttendanceStatus.FINISHED);
    }

    private MvcResult rate(String attendanceId, String token, int score, String comment) throws Exception {
        return mvc.perform(post("/attendances/{id}/rating", attendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new AttendanceRatingRequest(score, comment))))
                .andReturn();
    }

    private long profCount() {
        return professionalRepository.findById(UUID.fromString(professionalId)).orElseThrow().getRatingCount();
    }

    private long profSum() {
        return professionalRepository.findById(UUID.fromString(professionalId)).orElseThrow().getRatingSum();
    }

    private long serviceCount(UUID serviceItemId) {
        return serviceItemRepository.findById(serviceItemId).orElseThrow().getRatingCount();
    }

    @AfterEach
    void clearQueue() {
        attendanceRatingRepository.deleteAll();
        attendanceRepository.deleteAll();
    }

    // ─────────────────────────────────────────────────────────────
    // Testes
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("1 servico -> avaliar FINISHED alimenta profissional E o ServiceItem; serviceItemId presente")
    void rate_singleService_feedsProfessionalAndServiceItem() throws Exception {
        Q q = createWalkin(List.of(service1Id));
        finish(q.id());

        long profCountBefore = profCount();
        long profSumBefore = profSum();
        long svcCountBefore = serviceCount(service1Id);

        MvcResult r = rate(q.id(), q.token(), 5, "Excelente");
        assertThat(r.getResponse().getStatus()).as("rating criado").isEqualTo(201);

        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        assertThat(body.getInt("score")).isEqualTo(5);
        assertThat(body.getString("attendanceId")).isEqualTo(q.id());
        assertThat(body.getString("serviceItemId")).isEqualTo(service1Id.toString());
        assertThat(body.getDouble("professionalRatingAverage")).isEqualTo(5.0);

        assertThat(profCount()).as("prof +1 voto").isEqualTo(profCountBefore + 1);
        assertThat(profSum()).as("prof +5 na soma").isEqualTo(profSumBefore + 5);
        assertThat(serviceCount(service1Id)).as("servico +1 voto").isEqualTo(svcCountBefore + 1);
    }

    @Test
    @Order(11)
    @DisplayName("2+ servicos -> avaliar alimenta SO o profissional; serviceItemId null; servicos intactos")
    void rate_combo_feedsOnlyProfessional() throws Exception {
        Q q = createWalkin(List.of(service1Id, service2Id));
        finish(q.id());

        long profCountBefore = profCount();
        long s1Before = serviceCount(service1Id);
        long s2Before = serviceCount(service2Id);

        MvcResult r = rate(q.id(), q.token(), 4, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);

        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        assertThat(body.isNull("serviceItemId")).as("combo -> sem ServiceItem").isTrue();

        assertThat(profCount()).as("prof +1").isEqualTo(profCountBefore + 1);
        assertThat(serviceCount(service1Id)).as("servico1 intacto").isEqualTo(s1Before);
        assertThat(serviceCount(service2Id)).as("servico2 intacto").isEqualTo(s2Before);
    }

    @Test
    @Order(12)
    @DisplayName("Segundo voto no mesmo atendimento -> 409")
    void rate_secondVote_409() throws Exception {
        Q q = createWalkin(List.of(service1Id));
        finish(q.id());

        assertThat(rate(q.id(), q.token(), 5, "primeira").getResponse().getStatus()).isEqualTo(201);

        long profCountBefore = profCount();
        MvcResult second = rate(q.id(), q.token(), 1, "segunda");
        assertThat(second.getResponse().getStatus()).as("2o voto bloqueado").isEqualTo(409);
        assertThat(profCount()).as("agregado nao muda no 2o voto").isEqualTo(profCountBefore);
    }

    @Test
    @Order(13)
    @DisplayName("Avaliar atendimento nao-FINISHED (SCHEDULED) -> 400")
    void rate_notFinished_400() throws Exception {
        Q q = createWalkin(List.of(service1Id)); // fica SCHEDULED

        MvcResult r = rate(q.id(), q.token(), 5, null);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(attendanceRatingRepository.existsByAttendanceId(UUID.fromString(q.id()))).isFalse();
    }

    @Test
    @Order(14)
    @DisplayName("Outro cliente (nao-dono) avaliando -> 403")
    void rate_notOwner_403() throws Exception {
        Q owner = createWalkin(List.of(service1Id));
        finish(owner.id());

        var intruder = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        MvcResult r = rate(owner.id(), intruder.token(), 5, "nao sou o dono");
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(attendanceRatingRepository.existsByAttendanceId(UUID.fromString(owner.id()))).isFalse();
    }

    @Test
    @Order(15)
    @DisplayName("Score fora de 1..5 -> 422 (validacao de campo)")
    void rate_invalidScore_422() throws Exception {
        Q q = createWalkin(List.of(service1Id));
        finish(q.id());

        assertThat(rate(q.id(), q.token(), 0, null).getResponse().getStatus()).isEqualTo(422);
        assertThat(rate(q.id(), q.token(), 6, null).getResponse().getStatus()).isEqualTo(422);
        assertThat(attendanceRatingRepository.existsByAttendanceId(UUID.fromString(q.id()))).isFalse();
    }

    @Test
    @Order(16)
    @DisplayName("Media denormalizada agrega multiplos votos no ServiceItem (5 e 3 -> 4.0)")
    void rate_average_aggregatesAcrossVotes() throws Exception {
        long svcCountBefore = serviceCount(service2Id);
        long svcSumBefore = serviceItemRepository.findById(service2Id).orElseThrow().getRatingSum();

        Q a = createWalkin(List.of(service2Id));
        finish(a.id());
        assertThat(rate(a.id(), a.token(), 5, null).getResponse().getStatus()).isEqualTo(201);

        Q b = createWalkin(List.of(service2Id));
        finish(b.id());
        assertThat(rate(b.id(), b.token(), 3, null).getResponse().getStatus()).isEqualTo(201);

        ServiceItem svc = serviceItemRepository.findById(service2Id).orElseThrow();
        assertThat(svc.getRatingCount()).isEqualTo(svcCountBefore + 2);
        assertThat(svc.getRatingSum()).isEqualTo(svcSumBefore + 8);
        // Media do proprio servico (isolado no setup: sem votos previos) = (5+3)/2 = 4.0
        assertThat(svc.getRatingAverage()).isEqualTo(4.0);
    }

    @AfterAll
    void tearDown() {
        attendanceRatingRepository.deleteAll();
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
