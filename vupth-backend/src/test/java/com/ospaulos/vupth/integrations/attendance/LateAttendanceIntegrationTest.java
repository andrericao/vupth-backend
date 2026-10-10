package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — item #19: status LATE (cliente atrasado / no-show parcial).
 *
 * Regras testadas:
 *  - PATCH /attendances/{id}/late: cliente cai 1 posicao (atras do proximo) mas NAO sai da fila (vira LATE).
 *  - Marcar LATE de novo: cai mais uma posicao.
 *  - PATCH /attendances/{id}/start: aceita LATE direto (atende o atrasado quando chega).
 *  - PATCH /attendances/{id}/undo-late: volta a SCHEDULED na posicao atual.
 *  - Erros: status invalido (400), undo de nao-LATE (400), profissional errado (403/404), sem token (401),
 *    token CLIENT sem role PROFESSIONAL (403).
 *
 * A ordem da fila e controlada via o scheduledTime passado no POST (a entidade usa esse valor;
 * a fila ordena por scheduledTime ASC e inclui SCHEDULED + LATE). Cada cenario usa uma janela de
 * tempo propria (offset crescente) para nao interferir nos demais; as assertivas sao RELATIVAS.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LateAttendanceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    String clientToken;          // CLIENT puro (sem role PROFESSIONAL)
    String anotherProfToken;     // PROFESSIONAL de OUTRA loja (violacao de ownership)
    List<UUID> serviceItemIds;

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

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        var anotherUser = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var anotherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, anotherUser.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, anotherUser.token(), anotherEstab.id());
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);
        TestUserUtils.startShift(professionalRepository, anotherEstab.id());
        this.anotherProfToken = anotherUser.token();

        log.info("[Late] setup — estab={} prof={}", establishmentId, professionalId);
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /** Cria um attendance SCHEDULED para um CLIENT novo, com o scheduledTime dado. Retorna o id. */
    private String createScheduled(int minutesFromNow) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(client.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(minutesFromNow),
                30,
                OffsetDateTime.now().plusMinutes(minutesFromNow));
        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(result.getResponse().getContentAsString()).getString("id");
    }

    private AttendanceStatus statusOf(String id) {
        return attendanceRepository.findById(UUID.fromString(id)).orElseThrow().getStatus();
    }

    /** Posicao (indice) do attendance na fila de espera (SCHEDULED + LATE), -1 se ausente. */
    private int waitingIndexOf(String id) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().toString().equals(id)) return i;
        }
        return -1;
    }

    private void markLate(String id, String token) throws Exception {
        mvc.perform(patch("/attendances/{id}/late", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // Happy path
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("PATCH /late — cliente vira LATE e cai atras do proximo (1 posicao), sem sair da fila")
    void markLate_fallsBehindNext() throws Exception {
        String c1 = createScheduled(100);
        String c2 = createScheduled(101);

        // antes: c1 esta a frente de c2
        assertThat(waitingIndexOf(c1)).isLessThan(waitingIndexOf(c2));

        markLate(c1, ownerProfToken);

        // depois: c1 ainda na fila, agora ATRAS de c2, e com status LATE
        assertThat(statusOf(c1)).isEqualTo(AttendanceStatus.LATE);
        assertThat(waitingIndexOf(c1))
                .as("c1 atrasado deve cair atras de c2")
                .isGreaterThan(waitingIndexOf(c2));
    }

    @Test
    @Order(11)
    @DisplayName("PATCH /late de novo — cai mais uma posicao")
    void markLate_again_fallsFurther() throws Exception {
        String c5 = createScheduled(200);
        String c6 = createScheduled(201);
        String c7 = createScheduled(202);

        markLate(c5, ownerProfToken); // [c6, c5, c7]
        assertThat(waitingIndexOf(c5)).isGreaterThan(waitingIndexOf(c6));
        assertThat(waitingIndexOf(c5)).isLessThan(waitingIndexOf(c7));

        markLate(c5, ownerProfToken); // [c6, c7, c5]
        assertThat(statusOf(c5)).isEqualTo(AttendanceStatus.LATE);
        assertThat(waitingIndexOf(c5))
                .as("apos 2 marcacoes, c5 deve estar atras de c6 e c7")
                .isGreaterThan(waitingIndexOf(c7));
    }

    @Test
    @Order(12)
    @DisplayName("PATCH /undo-late — cliente LATE volta a SCHEDULED")
    void undoLate_restoresScheduled() throws Exception {
        String c3 = createScheduled(300);
        createScheduled(301); // c4: garante que c3 nao e o ultimo (tem como cair)

        markLate(c3, ownerProfToken);
        assertThat(statusOf(c3)).isEqualTo(AttendanceStatus.LATE);

        mvc.perform(patch("/attendances/{id}/undo-late", c3)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());

        assertThat(statusOf(c3)).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Test
    @Order(13)
    @DisplayName("PATCH /start — aceita um cliente LATE direto (atende o atrasado quando chega)")
    void start_acceptsLateAttendance() throws Exception {
        String late = createScheduled(400);
        createScheduled(401); // proximo, para o late ter como cair

        markLate(late, ownerProfToken);
        assertThat(statusOf(late)).isEqualTo(AttendanceStatus.LATE);

        mvc.perform(patch("/attendances/{id}/start", late)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());

        assertThat(statusOf(late)).isEqualTo(AttendanceStatus.IN_PROGRESS);

        // cleanup: libera o profissional
        mvc.perform(patch("/attendances/{id}/finish", late)
                        // pagamento-pix: pago no Finalizar, para o portao nao barrar o mesmo cliente depois
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_received\":true}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(14)
    @DisplayName("DELETE /professional-queue/{id}/remove — remove um cliente LATE da fila → REMOVED")
    void removeFromQueue_acceptsLateAttendance() throws Exception {
        String late = createScheduled(900);
        createScheduled(901); // proximo, para o late ter como cair

        markLate(late, ownerProfToken);
        assertThat(statusOf(late)).isEqualTo(AttendanceStatus.LATE);

        mvc.perform(delete("/professional-queue/{id}/remove", late)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andDo(print());

        assertThat(statusOf(late)).isEqualTo(AttendanceStatus.REMOVED);
        assertThat(waitingIndexOf(late))
                .as("cliente removido sai da fila de espera")
                .isEqualTo(-1);
    }

    // ─────────────────────────────────────────────────────────────
    // Errors
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("PATCH /late — 400 status invalido (attendance IN_PROGRESS nao pode virar LATE)")
    void markLate_invalidStatus_400() throws Exception {
        String inProgress = createScheduled(500);

        mvc.perform(patch("/attendances/{id}/start", inProgress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        mvc.perform(patch("/attendances/{id}/late", inProgress)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isBadRequest())
                .andDo(print());

        // cleanup
        mvc.perform(patch("/attendances/{id}/finish", inProgress)
                        // pagamento-pix: pago no Finalizar, para o portao nao barrar o mesmo cliente depois
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_received\":true}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
    }

    @Test
    @Order(21)
    @DisplayName("PATCH /undo-late — 400 quando o attendance nao esta LATE (esta SCHEDULED)")
    void undoLate_notLate_400() throws Exception {
        String scheduled = createScheduled(600);

        mvc.perform(patch("/attendances/{id}/undo-late", scheduled)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isBadRequest())
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("PATCH /late — 403/404 profissional de outra loja tentando marcar attendance alheio")
    void markLate_wrongProfessional_forbidden() throws Exception {
        String c = createScheduled(700);
        createScheduled(701);

        mvc.perform(patch("/attendances/{id}/late", c)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", anotherProfToken))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(403, 404))
                .andDo(print());

        // nao mudou
        assertThat(statusOf(c)).isEqualTo(AttendanceStatus.SCHEDULED);
    }

    @Test
    @Order(23)
    @DisplayName("PATCH /late — 401 sem token")
    void markLate_noToken_401() throws Exception {
        mvc.perform(patch("/attendances/{id}/late", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("PATCH /late — 403 token CLIENT nao tem role PROFESSIONAL")
    void markLate_clientToken_403() throws Exception {
        String c = createScheduled(800);
        createScheduled(801);

        mvc.perform(patch("/attendances/{id}/late", c)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(25)
    @DisplayName("PATCH /late — 404 attendance inexistente")
    void markLate_notFound_404() throws Exception {
        mvc.perform(patch("/attendances/{id}/late", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isNotFound())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
