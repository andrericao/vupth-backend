package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — Partner S7: promover atendimento ao 1o lugar.
 *
 * Regra: <b>PATCH /attendances/{id}/promote</b> (PROFESSIONAL dono) move um atendimento em espera
 * para o indice 0 da fila (proximo a ser atendido), empurrando os demais, sem inicia-lo. So o
 * profissional dono (JWT), so em SCHEDULED. No-op se ja for o 1o.
 *
 * Fila limpa por teste (@AfterEach) -> posicoes ABSOLUTAS deterministicas.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PromoteToFrontIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    String clientToken;       // CLIENT puro (sem role PROFESSIONAL)
    String anotherProfToken;  // PROFESSIONAL de OUTRA loja (violacao de ownership)

    List<UUID> serviceItemIds;

    private record Q(String id, String token, String clientId) {}

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");

        // Remove refeicoes seedadas para nao interferir na fila (mesma premissa do FitIn).
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        var anotherUser = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var anotherEstab = TestUserUtils.becomeOwner(mvc, mapper, faker, anotherUser.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, anotherUser.token(), anotherEstab.id());
        this.anotherProfToken = anotherUser.token();

        log.info("[Promote] setup — estab={} prof={}", establishmentId, professionalId);
    }

    @AfterEach
    void clearQueue() {
        attendanceRepository.deleteAll();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /** Cria um attendance SCHEDULED (30 min) para um CLIENT novo, no scheduledTime dado. */
    private Q createScheduled(int minutesFromNow) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(client.id()),
                serviceItemIds,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(minutesFromNow),
                30,
                OffsetDateTime.now().plusMinutes(minutesFromNow));
        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        String id = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        return new Q(id, client.token(), client.id());
    }

    private MvcResult promote(String id, String token) throws Exception {
        return mvc.perform(patch("/attendances/{id}/promote", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token))
                .andReturn();
    }

    /** Posicao (indice) do attendance na fila de espera (SCHEDULED + LATE), -1 se ausente. */
    private int waitingIndexOf(String id) {
        List<Attendance> waiting = attendanceRepository.findWaitingQueueByProfessionalAndEstablishment(
                UUID.fromString(professionalId), UUID.fromString(establishmentId));
        for (int i = 0; i < waiting.size(); i++) {
            if (waiting.get(i).getId().toString().equals(id)) return i;
        }
        return -1;
    }

    // ── Casos ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("promove um atendimento do fim para o 1o lugar; os demais descem")
    void promote_movesToFront() throws Exception {
        Q a = createScheduled(10); // cabeca
        Q b = createScheduled(20);
        Q c = createScheduled(30); // ultimo
        assertThat(waitingIndexOf(c.id())).isEqualTo(2);

        promote(c.id(), ownerProfToken);

        assertThat(waitingIndexOf(c.id())).isEqualTo(0);
        assertThat(waitingIndexOf(a.id())).isEqualTo(1);
        assertThat(waitingIndexOf(b.id())).isEqualTo(2);
    }

    @Test
    @Order(20)
    @DisplayName("promover quem ja e o 1o -> 200, no-op (segue no indice 0)")
    void promote_alreadyFirst_isNoOp() throws Exception {
        Q a = createScheduled(10); // ja cabeca
        Q b = createScheduled(20);

        promote(a.id(), ownerProfToken);

        assertThat(waitingIndexOf(a.id())).isEqualTo(0);
        assertThat(waitingIndexOf(b.id())).isEqualTo(1);
    }

    @Test
    @Order(30)
    @DisplayName("[ERRO] profissional de outra loja -> 403")
    void promote_notOwner_forbidden() throws Exception {
        Q a = createScheduled(10);
        createScheduled(20);

        assertThat(promote(a.id(), anotherProfToken).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Order(40)
    @DisplayName("[ERRO] token CLIENT (sem role PROFESSIONAL) -> 403")
    void promote_clientRole_forbidden() throws Exception {
        Q a = createScheduled(10);

        assertThat(promote(a.id(), clientToken).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @Order(50)
    @DisplayName("[ERRO] status invalido (cancelado) -> 400")
    void promote_invalidStatus_badRequest() throws Exception {
        Q a = createScheduled(10);
        // cancela -> vira CANCELLED (fora da fila de espera)
        mvc.perform(put("/attendances/{id}/cancel", a.id())
                        .header("Authorization", a.token()))
                .andExpect(status().isOk());

        assertThat(promote(a.id(), ownerProfToken).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @Order(60)
    @DisplayName("[ERRO] attendance inexistente -> 404")
    void promote_notFound() throws Exception {
        assertThat(promote(UUID.randomUUID().toString(), ownerProfToken).getResponse().getStatus())
                .isEqualTo(404);
    }
}
