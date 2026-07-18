package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — manifestar INTENÇÃO de atendimento (backlog #4 / status INTENTION), fatia 1.
 *
 * <p>Com a loja FECHADA (ou o dia cheio), o cliente registrado pede para ser atendido num horário
 * PRETENDIDO futuro ({@code desiredTime}). A intenção NÃO entra na fila viva na hora: nasce como
 * INTENTION (fora da fila) e será promovida a SCHEDULED perto do horário (fatia 3).
 *
 * <p>Valida: aceite mesmo com a loja CLOSED (desde que dentro do expediente); 422 fora do
 * expediente; 422 além do horizonte; 400 no passado; 409 CHEIO (não cabe antes do fechamento);
 * e a invariante de que a INTENTION não vaza para a fila viva.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntentionCreateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
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

        // Sem intervalos (refeição) atravessando — mantém a projeção previsível.
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)); // estimatedMinutes = 30
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        log.info("[IntentionCreate] setup — estab={} prof={}", establishmentId, professionalId);
    }

    /** POST /attendances/intentions cru para um CLIENT novo. */
    private ResultActions postIntention(OffsetDateTime desiredTime) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        CreateIntentionRequest request = new CreateIntentionRequest(
                UUID.fromString(professionalId), serviceItemIds, desiredTime);
        return mvc.perform(post("/attendances/intentions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", client.token())
                .content(mapper.writeValueAsString(request)));
    }

    private void setStatusAndHours(OpenStatus status, OffsetTime opening, OffsetTime closing) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(status);
        e.setOpeningTime(opening);
        e.setClosingTime(closing);
        establishmentRepository.save(e);
    }

    /**
     * Janela 24h (UTC) via {@code opening == closing} — o modelo trata isso como expediente
     * degenerado "sempre aberto" ({@code isWithinBusinessHours}/{@code fitsBeforeClosing}). Evita a
     * borda de meia-noite: com {@code [00:00, 23:59:59)} um serviço que começa perto do fim do dia
     * estoura o fechamento e vira 409 espúrio quando a suíte roda à noite (UTC). 24h real cobre
     * qualquer horário futuro de forma determinística, independente do relógio.
     */
    private void openFullDay(OpenStatus status) {
        setStatusAndHours(status, OffsetTime.of(0, 0, 0, 0, ZoneOffset.UTC),
                OffsetTime.of(0, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    @Order(10)
    @DisplayName("loja CLOSED mas dentro do expediente — 201 cria INTENTION com estimativa")
    void closed_withinHours_201() throws Exception {
        openFullDay(OpenStatus.CLOSED); // fechada agora, mas o expediente cobre o horário pretendido

        postIntention(OffsetDateTime.now().plusHours(2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INTENTION"))
                .andExpect(jsonPath("$.desiredTime").exists())
                .andExpect(jsonPath("$.estimatedStartTime").exists());
    }

    @Test
    @Order(20)
    @DisplayName("desiredTime FORA do expediente — 422 INTENTION_OUTSIDE_BUSINESS_HOURS")
    void outsideBusinessHours_422() throws Exception {
        OffsetDateTime desired = OffsetDateTime.now().plusHours(2);
        OffsetTime desiredUtc = desired.toOffsetTime().withOffsetSameInstant(ZoneOffset.UTC);
        // janela [desired+3h, desired+4h): o horário pretendido cai ANTES da abertura.
        setStatusAndHours(OpenStatus.CLOSED, desiredUtc.plusHours(3), desiredUtc.plusHours(4));

        postIntention(desired)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_OUTSIDE_BUSINESS_HOURS"));
    }

    @Test
    @Order(30)
    @DisplayName("desiredTime além do horizonte (31 dias) — 422 INTENTION_HORIZON_EXCEEDED")
    void beyondHorizon_422() throws Exception {
        openFullDay(OpenStatus.CLOSED);

        postIntention(OffsetDateTime.now().plusDays(31))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_HORIZON_EXCEEDED"));
    }

    @Test
    @Order(40)
    @DisplayName("desiredTime no passado — 400")
    void past_400() throws Exception {
        openFullDay(OpenStatus.CLOSED);

        postIntention(OffsetDateTime.now().minusHours(1))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(50)
    @DisplayName("não cabe antes do fechamento — 409 PROFESSIONAL_AGENDA_FULL (CHEIO)")
    void agendaFull_409() throws Exception {
        OffsetDateTime desired = OffsetDateTime.now().plusHours(2);
        OffsetTime desiredUtc = desired.toOffsetTime().withOffsetSameInstant(ZoneOffset.UTC);
        // Fecha só 5min depois do horário pretendido; o serviço dura 30min => estoura o fechamento.
        setStatusAndHours(OpenStatus.CLOSED, OffsetTime.of(0, 0, 0, 0, ZoneOffset.UTC),
                desiredUtc.plusMinutes(5));

        postIntention(desired)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_AGENDA_FULL"));
    }

    @Test
    @Order(60)
    @DisplayName("INTENTION não vaza para a fila viva (não aparece no waiting queue)")
    void intentionDoesNotLeakIntoQueue() throws Exception {
        openFullDay(OpenStatus.CLOSED);

        String body = postIntention(OffsetDateTime.now().plusHours(3))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID intentionId = UUID.fromString(new org.json.JSONObject(body).getString("id"));

        // A intenção existe como INTENTION...
        Attendance saved = attendanceRepository.findById(intentionId).orElseThrow();
        assertTrue(saved.getStatus() == com.ospaulos.vupth.domain.attendance.AttendanceStatus.INTENTION);

        // ...mas NÃO aparece na fila de espera do profissional (só SCHEDULED/LATE).
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        assertFalse(waiting.stream().anyMatch(a -> a.getId().equals(intentionId)),
                "INTENTION não deve aparecer na fila viva");
    }
}
