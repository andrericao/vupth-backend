package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — gate ao fechar a loja com fila/atendimento ativo (Partner BUG6).
 *
 * {@code PATCH /establishment/{id}/open-status} com {@code CLOSED}/{@code TEMPORARILY_CLOSED}
 * deve dar <b>409 ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES</b> enquanto houver atendimento
 * SCHEDULED/LATE/IN_PROGRESS/PAUSED. Sem fila ativa, fechar volta a 200.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CloseEstablishmentGateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    UUID walkinId;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Owner Professional");

        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Força a loja ABERTA (o gate de fechar só vale saindo de OPEN).
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);

        log.info("[CloseGate] setup — estab={} prof={}", establishmentId, professionalId);
    }

    /** Cria um walk-in (cliente novo) na fila, 201, e devolve o id do attendance. */
    private UUID createWalkin() throws Exception {
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
        return UUID.fromString(new JSONObject(r.getResponse().getContentAsString()).getString("id"));
    }

    @Test
    @Order(10)
    @DisplayName("PATCH open-status CLOSED — 409 com cliente na fila (BUG6)")
    void close_withActiveQueue_409() throws Exception {
        this.walkinId = createWalkin();

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES"));

        // não mudou de status: continua OPEN
        assertThat(establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow()
                .getOpenStatus()).isEqualTo(OpenStatus.OPEN);
    }

    @Test
    @Order(11)
    @DisplayName("PATCH open-status TEMPORARILY_CLOSED — 409 com cliente na fila (BUG6)")
    void temporarilyClose_withActiveQueue_409() throws Exception {
        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"TEMPORARILY_CLOSED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES"));
    }

    @Test
    @Order(20)
    @DisplayName("PATCH open-status CLOSED — 200 quando a fila esvazia")
    void close_afterQueueEmpty_200() throws Exception {
        // esvazia a fila: o único atendimento vira CANCELLED (terminal, não conta)
        Attendance a = attendanceRepository.findById(walkinId).orElseThrow();
        a.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(a);

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("CLOSED"));
    }
}
