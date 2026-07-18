package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.attendance.service.AttendanceService;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — PROMOÇÃO da intenção de atendimento (backlog #4, fatia 3). Exercita o núcleo
 * {@link AttendanceService#promoteIntention(UUID)} chamado pelo gatilho event-driven: o flip atômico
 * {@code INTENTION -> SCHEDULED} que insere a intenção na fila viva (cauda = posição do ETA no instante),
 * e o comportamento auto-limpante nas corridas (já promovida / cancelada).
 *
 * <p>A janela de disparo (≤15min) e a re-consulta da cabeça são do {@code IntentionPromotionScheduler}
 * (coberto em {@code IntentionPromotionSchedulerTest}); aqui foca-se no efeito persistido da promoção,
 * sem depender de temporização real.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntentionPromotionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired AttendanceService attendanceService;

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

        // Sem intervalos (refeição) — projeção da fila previsível.
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)); // 30min
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Expediente 24h (UTC) via opening == closing — "sempre aberto" no modelo. Evita a borda de
        // meia-noite (com [00:00, 23:59:59) desiredTimes à noite estouram o fechamento = 409 espúrio).
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.CLOSED);
        e.setOpeningTime(OffsetTime.of(0, 0, 0, 0, ZoneOffset.UTC));
        e.setClosingTime(OffsetTime.of(0, 0, 0, 0, ZoneOffset.UTC));
        establishmentRepository.save(e);
    }

    /** Uma intenção criada + o userId do cliente dono. */
    private record OwnedIntention(UUID id, UUID clientId) {}

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
        return new OwnedIntention(id, UUID.fromString(client.id()));
    }

    private boolean inLiveQueue(UUID attendanceId) {
        return attendanceRepository.findWaitingQueueByEstablishment(UUID.fromString(establishmentId))
                .stream().anyMatch(a -> a.getId().equals(attendanceId));
    }

    @Test
    @Order(10)
    @DisplayName("promoteIntention — INTENTION->SCHEDULED, entra na fila viva, retorna estab+cliente")
    void promote_happy() throws Exception {
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        // Antes: fora da fila viva.
        assertFalse(inLiveQueue(intention.id()), "intenção não deve estar na fila viva antes da promoção");

        OffsetDateTime beforePromote = OffsetDateTime.now().minusSeconds(2);
        Optional<AttendanceService.PromotionResult> result = attendanceService.promoteIntention(intention.id());

        assertTrue(result.isPresent(), "promoção deve ocorrer");
        assertEquals(UUID.fromString(establishmentId), result.get().establishmentId());
        assertEquals(intention.clientId(), result.get().clientId());
        assertEquals(intention.id(), result.get().attendanceId());

        Attendance saved = attendanceRepository.findById(intention.id()).orElseThrow();
        assertEquals(AttendanceStatus.SCHEDULED, saved.getStatus());
        assertNotNull(saved.getScheduledTime());
        // scheduledTime foi setado para "agora" (cauda da fila), não o desiredTime (2h à frente).
        assertFalse(saved.getScheduledTime().isBefore(beforePromote),
                "scheduledTime deve ser ~agora (posição de ETA no instante da promoção)");
        // desiredTime persistido segue como registro do horário pretendido.
        assertNotNull(saved.getDesiredTime());
        assertTrue(inLiveQueue(intention.id()), "depois da promoção deve aparecer na fila viva");
    }

    @Test
    @Order(20)
    @DisplayName("promoteIntention — cancelada antes: no-op (Optional.empty, segue CANCELLED)")
    void promote_afterCancel_noop() throws Exception {
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        Attendance a = attendanceRepository.findById(intention.id()).orElseThrow();
        a.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(a);

        Optional<AttendanceService.PromotionResult> result = attendanceService.promoteIntention(intention.id());
        assertTrue(result.isEmpty(), "não promove o que não é mais INTENTION");
        assertEquals(AttendanceStatus.CANCELLED,
                attendanceRepository.findById(intention.id()).orElseThrow().getStatus());
    }

    @Test
    @Order(30)
    @DisplayName("promoteIntention — idempotente: 2ª chamada não re-promove (já SCHEDULED)")
    void promote_twice_idempotent() throws Exception {
        OwnedIntention intention = createIntention(OffsetDateTime.now().plusHours(2));

        assertTrue(attendanceService.promoteIntention(intention.id()).isPresent());
        // 2ª promoção: já é SCHEDULED -> no-op.
        assertTrue(attendanceService.promoteIntention(intention.id()).isEmpty());
        assertEquals(AttendanceStatus.SCHEDULED,
                attendanceRepository.findById(intention.id()).orElseThrow().getStatus());
    }

    @Test
    @Order(40)
    @DisplayName("promoteIntention — id inexistente: no-op (Optional.empty)")
    void promote_notFound_noop() {
        assertTrue(attendanceService.promoteIntention(UUID.randomUUID()).isEmpty());
    }

    @Test
    @Order(50)
    @DisplayName("findEarliestIntention — devolve a de MENOR desiredTime entre várias")
    void earliestIntention_ordering() throws Exception {
        // Limpa intenções remanescentes dos testes anteriores para isolar a ordenação.
        attendanceRepository.findIntentionsByProfessional(UUID.fromString(professionalId))
                .forEach(a -> { a.setStatus(AttendanceStatus.CANCELLED); attendanceRepository.save(a); });

        OwnedIntention later = createIntention(OffsetDateTime.now().plusHours(5));
        OwnedIntention earlier = createIntention(OffsetDateTime.now().plusHours(3));

        Optional<Attendance> head = attendanceRepository.findEarliestIntention();
        assertTrue(head.isPresent());
        assertEquals(earlier.id(), head.get().getId(), "a cabeça é a de menor desiredTime");
        assertNotEquals(later.id(), head.get().getId());
    }
}
