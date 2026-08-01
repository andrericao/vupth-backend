package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
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

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — S1 (fatia 1): PATCH /professional/me/status.
 *
 * O profissional muda o proprio status (WORKING/ON_BREAK/OFF). Gates: entrar em expediente exige
 * ≥1 servico (422); ficar indisponivel com fila propria e barrado (409). Nesta fatia o open_status
 * ainda NAO e derivado — so o status por profissional e persistido/exposto.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalStatusIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    String profToken, establishmentId, professionalId, serviceItemId;
    String noServiceToken; // PROFESSIONAL sem nenhum servico

    @BeforeAll
    void setup() throws Exception {
        // Profissional COM servico (owner que tambem trabalha).
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.professionalId = owner.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof A");
        this.serviceItemId = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)).id();

        // Profissional SEM servico (loja propria, nenhum service item).
        var noSvc = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.noServiceToken = noSvc.token();
        var noSvcEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, noServiceToken);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, noServiceToken, noSvcEstab.id(), "Prof B");

        log.info("[ProfessionalStatus] setup — estab={} prof={}", establishmentId, professionalId);
    }

    @AfterEach
    void clean() {
        attendanceRepository.deleteAll();
    }

    private org.springframework.test.web.servlet.ResultActions patchStatus(String token, String status) throws Exception {
        return mvc.perform(patch("/professional/me/status")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content("{\"status\":\"" + status + "\"}"));
    }

    @Test
    @Order(10)
    @DisplayName("com servico: WORKING -> ON_BREAK -> OFF, todos 200 e refletidos no GET /me")
    void happyPath_transitions() throws Exception {
        patchStatus(profToken, "WORKING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("WORKING"));

        patchStatus(profToken, "ON_BREAK")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("ON_BREAK"));

        mvc.perform(get("/professional/me").header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("ON_BREAK"));

        patchStatus(profToken, "OFF")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("OFF"));
    }

    @Test
    @Order(20)
    @DisplayName("sem servico: WORKING e ON_BREAK -> 422 PROFESSIONAL_HAS_NO_SERVICE")
    void noService_cannotWork() throws Exception {
        patchStatus(noServiceToken, "WORKING")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_HAS_NO_SERVICE"));

        patchStatus(noServiceToken, "ON_BREAK")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_HAS_NO_SERVICE"));

        // OFF (nao conta como aberto) e permitido mesmo sem servico.
        patchStatus(noServiceToken, "OFF").andExpect(status().isOk());
    }

    @Test
    @Order(30)
    @DisplayName("OFF com fila propria -> 409 PROFESSIONAL_HAS_ACTIVE_ATTENDANCES")
    void off_blockedWithQueue() throws Exception {
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        // guest entra na fila do profissional (isento do gate C1)
        String guest = "{\"establishmentId\":\"" + establishmentId + "\",\"professionalId\":\"" + professionalId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"],\"clientName\":\"Fulano\"}";
        mvc.perform(post("/professional-queue/add-quest-attendance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(guest))
                .andExpect(status().isCreated());

        patchStatus(profToken, "OFF")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_HAS_ACTIVE_ATTENDANCES"));
    }

    @Test
    @Order(40)
    @DisplayName("status ausente -> 422 VALIDATION_ERROR")
    void missingStatus_validation() throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
