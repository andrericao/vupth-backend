package com.ospaulos.vupth.integrations.attendance;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.api.attendance.dto.request.UpdateIntentionRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — editar/cancelar INTENÇÃO de atendimento (backlog #4 / status INTENTION),
 * fatia 3. O cliente dono (por JWT) altera o horário pretendido (PATCH) ou cancela (DELETE) enquanto
 * a intenção ainda é INTENTION.
 *
 * <p>Valida: PATCH recalcula a estimativa (200); PATCH re-executa TODAS as invariantes do create
 * (422 fora do expediente); 403 não-dono; 404 inexistente; 400 quando não é mais INTENTION (já
 * cancelado/promovido). DELETE cancela (204 + status CANCELLED); 403 não-dono; 404 inexistente.
 * Cobre também a invariante {@code INTENTION_BEFORE_QUEUE_END} (422) — o horário pretendido não pode
 * ser anterior ao fim projetado da fila viva do profissional.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntentionUpdateCancelIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired TransactionTemplate tx;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();

    String ownerToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Owner Professional");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);

        // Sem intervalos (refeição) atravessando — mantém a projeção da fila previsível.
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)); // estimatedMinutes = 30
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        log.info("[IntentionUpdateCancel] setup — estab={} prof={}", establishmentId, professionalId);
    }

    /** Uma intenção criada + o token do cliente dono (para exercitar dono vs. não-dono). */
    private record OwnedIntention(String ownerClientToken, UUID id) {}

    /** Cria uma INTENTION para um CLIENT novo e devolve o id + o token do dono. */
    private OwnedIntention createIntention(OffsetDateTime desiredTime) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        CreateIntentionRequest request = new CreateIntentionRequest(
                UUID.fromString(professionalId), serviceItemIds, desiredTime);
        String body = mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(new JSONObject(body).getString("id"));
        return new OwnedIntention(client.token(), id);
    }

    private org.springframework.test.web.servlet.ResultActions patchIntention(
            UUID id, OffsetDateTime desiredTime, String token) throws Exception {
        UpdateIntentionRequest request = new UpdateIntentionRequest(desiredTime);
        return mvc.perform(patch("/attendances/intentions/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content(mapper.writeValueAsString(request)));
    }

    private org.springframework.test.web.servlet.ResultActions deleteIntention(UUID id, String token) throws Exception {
        return mvc.perform(delete("/attendances/intentions/" + id)
                .header("Authorization", token));
    }

    private void setStatusAndHours(OpenStatus status, LocalTime opening, LocalTime closing) {
        TestUserUtils.setStatusAndHours(tx, establishmentRepository, establishmentId, status, opening, closing);
    }

    /**
     * Janela 24h (UTC) via {@code opening == closing} — o modelo trata isso como expediente
     * degenerado "sempre aberto" ({@code isWithinBusinessHours}/{@code fitsBeforeClosing}). Evita a
     * borda de meia-noite: com {@code [00:00, 23:59:59)} intenções que se acumulam para perto do fim
     * do dia estouram o fechamento e viram 409 espúrio quando a suíte roda à noite (UTC). 24h real
     * cobre qualquer horário futuro de forma determinística, independente do relógio.
     */
    private void openFullDay(OpenStatus status) {
        setStatusAndHours(status, LocalTime.MIDNIGHT,
                LocalTime.MIDNIGHT);
    }

    private String freshClientToken() throws Exception {
        return TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
    }

    @Test
    @Order(10)
    @DisplayName("PATCH pelo dono — 200 recalcula estimativa e altera desiredTime")
    void update_happy_200() throws Exception {
        openFullDay(OpenStatus.CLOSED);
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        OffsetDateTime newDesired = OffsetDateTime.now().plusHours(3);
        patchIntention(intention.id(), newDesired, intention.ownerClientToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTENTION"))
                .andExpect(jsonPath("$.desiredTime").exists())
                .andExpect(jsonPath("$.estimatedStartTime").exists());

        Attendance saved = attendanceRepository.findById(intention.id()).orElseThrow();
        assertEquals(AttendanceStatus.INTENTION, saved.getStatus());
        // desiredTime persistido reflete a edição (~3h à frente, não mais 2h).
        assertEquals(newDesired.toInstant().getEpochSecond(),
                saved.getDesiredTime().toInstant().getEpochSecond());
    }

    @Test
    @Order(20)
    @DisplayName("PATCH por quem não é o dono — 403")
    void update_notOwner_403() throws Exception {
        openFullDay(OpenStatus.CLOSED);
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        patchIntention(intention.id(), OffsetDateTime.now().plusHours(3), freshClientToken())
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(30)
    @DisplayName("PATCH re-valida as invariantes — novo horário fora do expediente 422")
    void update_revalidatesOutsideHours_422() throws Exception {
        openFullDay(OpenStatus.CLOSED);
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        OffsetDateTime newDesired = OffsetDateTime.now().plusHours(2);
        LocalTime newDesiredUtc = BusinessClock.timeAt(newDesired);
        // janela [newDesired+3h, newDesired+4h): o novo horário pretendido cai ANTES da abertura.
        setStatusAndHours(OpenStatus.CLOSED, newDesiredUtc.plusHours(3), newDesiredUtc.plusHours(4));

        patchIntention(intention.id(), newDesired, intention.ownerClientToken())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_OUTSIDE_BUSINESS_HOURS"));
    }

    @Test
    @Order(40)
    @DisplayName("PATCH de intenção inexistente — 404")
    void update_notFound_404() throws Exception {
        patchIntention(UUID.randomUUID(), OffsetDateTime.now().plusHours(2), freshClientToken())
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(50)
    @DisplayName("DELETE pelo dono — 204 e status CANCELLED")
    void cancel_happy_204() throws Exception {
        openFullDay(OpenStatus.CLOSED);
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        deleteIntention(intention.id(), intention.ownerClientToken())
                .andExpect(status().isNoContent());

        Attendance saved = attendanceRepository.findById(intention.id()).orElseThrow();
        assertEquals(AttendanceStatus.CANCELLED, saved.getStatus());
    }

    @Test
    @Order(60)
    @DisplayName("DELETE por quem não é o dono — 403")
    void cancel_notOwner_403() throws Exception {
        openFullDay(OpenStatus.CLOSED);
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        deleteIntention(intention.id(), freshClientToken())
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(70)
    @DisplayName("PATCH depois de cancelada — 400 ATTENDANCE_INVALID_STATUS")
    void update_afterCancel_400() throws Exception {
        openFullDay(OpenStatus.CLOSED);
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        deleteIntention(intention.id(), intention.ownerClientToken())
                .andExpect(status().isNoContent());

        // Já não é mais INTENTION — nem editar nem re-cancelar.
        patchIntention(intention.id(), OffsetDateTime.now().plusHours(3), intention.ownerClientToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_INVALID_STATUS"));
        deleteIntention(intention.id(), intention.ownerClientToken())
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(80)
    @DisplayName("DELETE de intenção inexistente — 404")
    void cancel_notFound_404() throws Exception {
        deleteIntention(UUID.randomUUID(), freshClientToken())
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(90)
    @DisplayName("intenção antes do fim projetado da fila viva — 422 INTENTION_BEFORE_QUEUE_END")
    void create_beforeQueueEnd_422() throws Exception {
        // Loja ABERTA (dia inteiro) para admitir o walk-in que forma a fila viva.
        openFullDay(OpenStatus.OPEN);

        // Walk-in imediato (SCHEDULED, 30min) => fila viva projeta terminar ~30min à frente.
        var walkinClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest walkin = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId),
                UUID.fromString(walkinClient.id()), serviceItemIds,
                AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", walkinClient.token())
                        .content(mapper.writeValueAsString(walkin)))
                .andExpect(status().isCreated());

        // Intenção pretendida DENTRO da projeção da fila (agora+10min < agora+~30min) — recusada.
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        CreateIntentionRequest request = new CreateIntentionRequest(
                UUID.fromString(professionalId), serviceItemIds, OffsetDateTime.now().plusMinutes(10));
        mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_BEFORE_QUEUE_END"));
    }
}
