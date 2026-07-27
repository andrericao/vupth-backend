package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — visao de agenda (backlog #4, fatia 2). Lista as INTENCOES futuras (fora da
 * fila viva), congeladas no estimado da criacao, + o "quadro" da barra de encaixe.
 *
 * <p>Duas telas, mesma forma: {@code GET /attendances/intentions/professional/me} (a agenda do
 * profissional) e {@code GET /attendances/intentions/client/me} (os proprios agendamentos).</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IntentionAgendaIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();
    private final List<UUID> createdIntentions = new ArrayList<>();

    String ownerToken, professionalId, establishmentId;
    String clientToken, otherClientToken;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Owner Professional");

        // Sem intervalos atravessando — projecao previsivel.
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)); // 30min
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // 24h real (opening == closing) evita a borda de meia-noite (mesma tecnica do IntentionCreate).
        openFullDay(OpenStatus.CLOSED);

        this.clientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        this.otherClientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();

        // clientToken: 2 agendamentos com este profissional; otherClientToken: 1.
        createdIntentions.add(createIntention(clientToken, OffsetDateTime.now().plusHours(2)));
        createdIntentions.add(createIntention(clientToken, OffsetDateTime.now().plusHours(26)));
        createdIntentions.add(createIntention(otherClientToken, OffsetDateTime.now().plusHours(4)));

        log.info("[IntentionAgenda] setup — estab={} prof={} intentions={}",
                establishmentId, professionalId, createdIntentions);
    }

    @AfterAll
    void cleanup() {
        attendanceRepository.deleteAllById(createdIntentions);
    }

    private void openFullDay(OpenStatus status) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(status);
        e.setOpeningTime(LocalTime.MIDNIGHT);
        e.setClosingTime(LocalTime.MIDNIGHT);
        establishmentRepository.save(e);
    }

    private UUID createIntention(String token, OffsetDateTime desired) throws Exception {
        CreateIntentionRequest req = new CreateIntentionRequest(
                UUID.fromString(professionalId), serviceItemIds, desired);
        MvcResult r = mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(new JSONObject(r.getResponse().getContentAsString()).getString("id"));
    }

    @Test
    @Order(10)
    @DisplayName("professional/me -> as 3 intencoes do profissional + 1 frame com o quadro da barra")
    void professionalAgenda() throws Exception {
        mvc.perform(get("/attendances/intentions/professional/me")
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intentions.length()").value(3))
                .andExpect(jsonPath("$.intentions[0].status").value("INTENTION"))
                .andExpect(jsonPath("$.intentions[0].estimatedStartTime").exists())
                .andExpect(jsonPath("$.frames.length()").value(1))
                .andExpect(jsonPath("$.frames[0].professionalId").value(professionalId))
                .andExpect(jsonPath("$.frames[0].projectedQueueEnd").exists())
                .andExpect(jsonPath("$.frames[0].closingTime").exists());
    }

    @Test
    @Order(20)
    @DisplayName("client/me -> so os proprios 2 agendamentos + 1 frame (profissional unico)")
    void clientAgenda() throws Exception {
        mvc.perform(get("/attendances/intentions/client/me")
                        .header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intentions.length()").value(2))
                .andExpect(jsonPath("$.intentions[0].status").value("INTENTION"))
                .andExpect(jsonPath("$.frames.length()").value(1))
                .andExpect(jsonPath("$.frames[0].professionalId").value(professionalId));
    }

    @Test
    @Order(30)
    @DisplayName("client/me sem nenhum agendamento -> listas vazias")
    void clientAgendaEmpty() throws Exception {
        String freshClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        mvc.perform(get("/attendances/intentions/client/me")
                        .header("Authorization", freshClient))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intentions.length()").value(0))
                .andExpect(jsonPath("$.frames.length()").value(0));
    }

    @Test
    @Order(40)
    @DisplayName("professional/me com token de cliente puro -> 403")
    void professionalAgenda_clientForbidden() throws Exception {
        mvc.perform(get("/attendances/intentions/professional/me")
                        .header("Authorization", clientToken))
                .andExpect(status().isForbidden());
    }
}
