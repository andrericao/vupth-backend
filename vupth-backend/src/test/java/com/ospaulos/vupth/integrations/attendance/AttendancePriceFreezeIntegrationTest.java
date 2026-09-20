package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — Receita LIQUIDA, parte 3: o atendimento congela o preco e a divisao na criacao,
 * e a listagem devolve as tres partes. Mudar o preco do servico depois nao reescreve o atendimento.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendancePriceFreezeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    private String ownerToken, ownerId, establishmentId, serviceItemId, attendanceId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.ownerId = owner.id();

        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Dono Profissional");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(ownerId)));

        MvcResult created = mvc.perform(post("/establishment/{id}/service-items", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(String.format("""
                                {
                                  "establishmentId": "%s",
                                  "serviceItemType": "CORTE_MAQUINA",
                                  "customName": "Corte liquido",
                                  "price": 31.90,
                                  "estimatedMinutes": 30,
                                  "pro_receives": 30.00,
                                  "base_price": 30.00
                                }
                                """, establishmentId)))
                .andExpect(status().isCreated())
                .andReturn();
        this.serviceItemId = new JSONObject(created.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(1)
    @DisplayName("criar o atendimento congela o total e a divisao, e a listagem devolve as tres partes")
    void createAttendance_freezesSplit() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(ownerId), UUID.fromString(establishmentId), UUID.fromString(client.id()),
                List.of(UUID.fromString(serviceItemId)), AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);

        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        this.attendanceId = new JSONObject(result.getResponse().getContentAsString()).getString("id");

        mvc.perform(get("/attendances").param("establishmentId", establishmentId).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].totalValue", attendanceId).value(31.90))
                .andExpect(jsonPath("$[?(@.id == '%s')].proReceives", attendanceId).value(30.00))
                .andExpect(jsonPath("$[?(@.id == '%s')].intermediaryFee", attendanceId).value(1.20))
                .andExpect(jsonPath("$[?(@.id == '%s')].platformFee", attendanceId).value(0.70))
                .andDo(print());
    }

    @Test
    @Order(2)
    @DisplayName("mudar o preco do servico depois nao reescreve o atendimento, nem ao ser atualizado")
    void laterPriceChange_doesNotRewriteAttendance() throws Exception {
        Assumptions.assumeTrue(attendanceId != null, "Depende do createAttendance_freezesSplit");

        mvc.perform(patch("/professional-queue/{profId}/service-items/{itemId}", ownerId, serviceItemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"price\":53.90,\"pro_receives\":50.00,\"base_price\":50.00}"))
                .andExpect(status().isOk());

        // Uma escrita no atendimento (start) dispara o @PreUpdate — era ai que o total era recalculado.
        mvc.perform(patch("/attendances/{id}/start", attendanceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk());

        mvc.perform(get("/attendances").param("establishmentId", establishmentId).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].totalValue", attendanceId).value(31.90))
                .andExpect(jsonPath("$[?(@.id == '%s')].proReceives", attendanceId).value(30.00))
                .andExpect(jsonPath("$[?(@.id == '%s')].intermediaryFee", attendanceId).value(1.20))
                .andExpect(jsonPath("$[?(@.id == '%s')].platformFee", attendanceId).value(0.70))
                .andDo(print());
    }
}
