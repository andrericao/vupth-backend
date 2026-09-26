package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — modo de funcionamento por profissional: fila ou so agenda (D9; Andre 25/09).
 *
 * <p>So agenda fecha as duas portas da fila viva (walk-in e pedido de corte). O modo so troca antes
 * do primeiro cliente na fila de hoje.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OperationModeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String profToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    String clientId, clientToken;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof Modo");
        var service = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(service.id()));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientId = client.id();
        this.clientToken = client.token();
    }

    private ResultActions setMode(String mode) throws Exception {
        return mvc.perform(patch("/professional/me/operation-mode")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"" + mode + "\"}")
                .header("Authorization", profToken));
    }

    private ResultActions walkIn() throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(clientId),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        return mvc.perform(post("/attendances")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", clientToken)
                .content(mapper.writeValueAsString(request)));
    }

    private ResultActions cutRequest() throws Exception {
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"serviceItemIds\":[\"" + serviceItemIds.get(0) + "\"]}";
        return mvc.perform(post("/attendances/cut-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", clientToken)
                .content(body));
    }

    @Test
    @Order(10)
    @DisplayName("O profissional nasce em fila (QUEUE)")
    void defaultQueue() throws Exception {
        mvc.perform(get("/professional/me").header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation_mode").value("QUEUE"));
    }

    @Test
    @Order(20)
    @DisplayName("Loja fechada, so agenda: pedido de corte -> 409 PROFESSIONAL_AGENDA_ONLY")
    void agendaOnly_blocksCutRequest() throws Exception {
        setMode("AGENDA_ONLY").andExpect(status().isOk())
                .andExpect(jsonPath("$.operation_mode").value("AGENDA_ONLY"));

        cutRequest().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_AGENDA_ONLY"));
    }

    @Test
    @Order(30)
    @DisplayName("Loja aberta, so agenda: entrar na fila -> 409, e a vitrine avisa o app (operation_mode)")
    void agendaOnly_blocksWalkIn() throws Exception {
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.startShift(professionalRepository, establishmentId);

        walkIn().andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_AGENDA_ONLY"));

        MvcResult r = mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new JSONObject(r.getResponse().getContentAsString())
                .getJSONArray("professionals").getJSONObject(0).getString("operation_mode"))
                .isEqualTo("AGENDA_ONLY");
    }

    @Test
    @Order(40)
    @DisplayName("Sem ninguem na fila hoje, volta para fila: 200, e o walk-in entra")
    void backToQueue_beforeFirstClient() throws Exception {
        setMode("QUEUE").andExpect(status().isOk());

        walkIn().andExpect(status().isCreated());
    }

    @Test
    @Order(50)
    @DisplayName("Com alguem na fila hoje, o modo trava: 409 OPERATION_MODE_LOCKED")
    void locked_afterFirstClient() throws Exception {
        setMode("AGENDA_ONLY").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_MODE_LOCKED"));

        mvc.perform(get("/professional/me").header("Authorization", profToken))
                .andExpect(jsonPath("$.operation_mode").value("QUEUE"));
    }

    @Test
    @Order(60)
    @DisplayName("Pedir o modo em que ja esta nao trava (idempotente)")
    void sameMode_idempotent() throws Exception {
        setMode("QUEUE").andExpect(status().isOk());
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
