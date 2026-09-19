package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — atendimento PAUSED continua visivel e retomavel (Partner BUG7).
 *
 * Bug: ao pausar um atendimento IN_PROGRESS, ele sumia de TODOS os read-models
 * (nem {@code current_attendance} nem {@code waiting_list} em
 * {@code GET /establishment/id/{id}/professionals-with-queue}) e o profissional nao
 * conseguia retomar. Correcao: o "atual" do profissional cai para o PAUSED na ausencia
 * de um IN_PROGRESS, ficando exposto e retomavel ate resume/finish.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PausedAttendanceVisibilityIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    String ownerToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    UUID attendanceId;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Owner Professional");

        // remove refeicoes automaticas para nao interferir no ETA/snapshot
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // O snapshot professionals-with-queue nao filtra por open-status, mas deixamos OPEN por realismo.
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);

        // 18/09: o snapshot filtra sim por EXPEDIENTE — quem esta OFF some da tela do cliente. O
        // profissional deste teste esta atendendo, entao ele chegou.
        TestUserUtils.putEveryoneToWork(professionalRepository, establishmentId);

        // cria walk-in e coloca em atendimento (IN_PROGRESS)
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
        this.attendanceId = UUID.fromString(new JSONObject(r.getResponse().getContentAsString()).getString("id"));

        mvc.perform(patch("/attendances/{id}/start", attendanceId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk());

        log.info("[PausedVisibility] setup — estab={} prof={} att={}", establishmentId, professionalId, attendanceId);
    }

    @Test
    @Order(10)
    @DisplayName("IN_PROGRESS aparece como current_attendance no professionals-with-queue")
    void inProgress_isCurrent() throws Exception {
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].current_attendance.id").value(attendanceId.toString()))
                .andExpect(jsonPath("$.professionals[0].current_attendance.status").value("IN_PROGRESS"));
    }

    @Test
    @Order(20)
    @DisplayName("Apos PAUSE o atendimento CONTINUA em current_attendance com status PAUSED (BUG7)")
    void paused_staysAsCurrent() throws Exception {
        mvc.perform(patch("/attendances/{id}/pause", attendanceId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk());

        // Regressao do BUG7: antes da correcao o current_attendance vinha null (orfao).
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].current_attendance.id").value(attendanceId.toString()))
                .andExpect(jsonPath("$.professionals[0].current_attendance.status").value("PAUSED"));
    }

    @Test
    @Order(30)
    @DisplayName("Atendimento pausado continua RETOMAVEL — resume 200 volta a IN_PROGRESS")
    void paused_isResumable() throws Exception {
        mvc.perform(patch("/attendances/{id}/resume", attendanceId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk());

        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].current_attendance.id").value(attendanceId.toString()))
                .andExpect(jsonPath("$.professionals[0].current_attendance.status").value("IN_PROGRESS"));
    }
}
