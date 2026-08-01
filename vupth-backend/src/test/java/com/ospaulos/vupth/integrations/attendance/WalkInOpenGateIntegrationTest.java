package com.ospaulos.vupth.integrations.attendance;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — gate de ENTRADA na fila (BUG5 parte C1).
 *
 * <p>Um walk-in imediato (POST /attendances sem {@code targetTime}) só entra na fila se a loja
 * estiver "aberta agora" = {@code open_status == OPEN} <b>e</b> horário atual dentro da janela
 * {@code [opening_time, closing_time)}. Fora disso → <b>409 ESTABLISHMENT_NOT_OPEN</b>.
 *
 * <p>A INTENÇÃO de fila ({@code targetTime} futuro, item #40) NÃO passa por esse gate — mira um
 * horário do expediente e é aceita mesmo com a loja marcada fechada.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalkInOpenGateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

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

        // Sem intervalos (refeição) atravessando — mantém a fila previsível pro caso da intenção.
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        // Quick-create nasce CLOSED — é justamente o estado do primeiro cenário.
        log.info("[WalkInGate] setup — estab={} prof={}", establishmentId, professionalId);
    }

    /** POST /attendances cru (sem expectativa de status) para um CLIENT novo, com targetTime opcional. */
    private org.springframework.test.web.servlet.ResultActions postWalkin(OffsetDateTime targetTime) throws Exception {
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
                targetTime);
        return mvc.perform(post("/attendances")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", client.token())
                .content(mapper.writeValueAsString(request)));
    }

    private void setStatusAndHours(OpenStatus status, LocalTime opening, LocalTime closing) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(status);
        e.setOpeningTime(opening);
        e.setClosingTime(closing);
        establishmentRepository.save(e);
    }

    @Test
    @Order(10)
    @DisplayName("walk-in com a loja CLOSED — 409 ESTABLISHMENT_NOT_OPEN")
    void walkin_closed_409() throws Exception {
        setStatusAndHours(OpenStatus.CLOSED, null, null);

        postWalkin(null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOT_OPEN"));
    }

    @Test
    @Order(20)
    @DisplayName("walk-in com a loja OPEN (sem janela) — 201 entra na fila")
    void walkin_openNoWindow_201() throws Exception {
        setStatusAndHours(OpenStatus.OPEN, null, null);

        postWalkin(null)
                .andExpect(status().isCreated());
    }

    @Test
    @Order(30)
    @DisplayName("walk-in com OPEN mas FORA do horário — 409 ESTABLISHMENT_NOT_OPEN (aberto agora efetivo)")
    void walkin_openOutsideHours_409() throws Exception {
        LocalTime now = BusinessClock.nowTime();
        // Janela no futuro [now+2h, now+3h): open_status OPEN mas isOpenNow() == false.
        setStatusAndHours(OpenStatus.OPEN, now.plusHours(2), now.plusHours(3));

        postWalkin(null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOT_OPEN"));
    }

    @Test
    @Order(40)
    @DisplayName("intenção de fila (targetTime futuro) NÃO passa pelo gate — 201 mesmo com a loja CLOSED")
    void intent_bypassesGate_evenWhenClosed() throws Exception {
        setStatusAndHours(OpenStatus.CLOSED, null, null);

        // targetTime bem à frente + fila vazia => intenção viável; o gate de "aberto agora" é ignorado.
        postWalkin(OffsetDateTime.now().plusMinutes(120))
                .andExpect(status().isCreated());
    }
}
