package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — S1 (fatia 3): "fechar fila" ({@code accepting_new}) + drenagem + auto-OFF.
 *
 * <p>Contrato (decisao Andre 2026-07-22): {@code PATCH /professional/me/accepting-new} com
 * {@code value=false} fecha a fila para CLIENTES NOVOS (walk-in imediato -> 409
 * {@code PROFESSIONAL_NOT_ACCEPTING_NEW}); quem ja esta segue sendo atendido (drenagem) e recebe o
 * anuncio {@code QUEUE_CLOSING}. O intent de fila (#40, {@code targetTime}) tambem e barrado
 * (nasce SCHEDULED na fila viva e prenderia a drenagem); agendar data futura segue livre via
 * INTENTION (#4, fora da fila). Ao finalizar o ultimo
 * atendimento com a fila fechada (ou fechar sem nada ativo), o profissional vira {@code OFF}
 * sozinho e — sendo o ultimo presente — a loja fecha (derivacao da fatia 2). Voltar de OFF para
 * expediente reabre a fila (turno novo).</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AcceptingNewDrainIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    // Push mockado para capturar o anuncio QUEUE_CLOSING (provider=none nos testes).
    @MockBean PushNotificationPublisher pushPublisher;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;
    String queuedClientId;      // cliente que entra na fila no Order 10 (recebe o anuncio)
    String queuedAttendanceId;  // attendance dele (drenado no Order 30)

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Prof Drain");

        // Sem refeicoes atravessando — mantem a viabilidade da intencao previsivel.
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));

        log.info("[AcceptingNew] setup — estab={} prof={}", establishmentId, professionalId);
    }

    private void patchStatus(String status) throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions patchAcceptingNew(boolean value) throws Exception {
        return mvc.perform(patch("/professional/me/accepting-new")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", ownerProfToken)
                .content("{\"value\":" + value + "}"));
    }

    /** POST /attendances cru para um CLIENT novo (walk-in quando targetTime=null). Devolve o ResultActions. */
    private org.springframework.test.web.servlet.ResultActions postWalkin(
            TestUserUtils.UserAuthAndToken client, OffsetDateTime targetTime) throws Exception {
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

    private void assertOpenStatus(String expected) throws Exception {
        mvc.perform(get("/establishment/id/" + establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value(expected));
    }

    @Test
    @Order(10)
    @DisplayName("fila aberta por padrao: WORKING abre a loja e o walk-in entra (201)")
    void defaultOpen_walkinEnters() throws Exception {
        patchStatus("WORKING"); // derivacao (fatia 2) abre a loja; sem janela de horario, isOpenNow=true
        assertOpenStatus("OPEN");

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.queuedClientId = client.id();
        var result = postWalkin(client, null)
                .andExpect(status().isCreated())
                .andReturn();
        this.queuedAttendanceId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(20)
    @DisplayName("fechar fila: walk-in e intent targetTime 409, INTENTION futura 201, quem ja esta recebe QUEUE_CLOSING")
    void closeQueue_blocksNewAnnouncesQueued() throws Exception {
        clearInvocations(pushPublisher);

        patchAcceptingNew(false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepting_new").value(false))
                .andExpect(jsonPath("$.work_status").value("WORKING")); // com fila viva NAO vira OFF

        // Anuncio ao cliente que JA esta na fila (push; o WS pessoal segue o mesmo caminho).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pushPublisher, atLeastOnce()).notifyUser(
                eq(UUID.fromString(queuedClientId)), anyString(), anyString(), dataCaptor.capture());
        assertThat(dataCaptor.getAllValues())
                .anyMatch(data -> "QUEUE_CLOSING".equals(data.get("type")));

        // Cliente NOVO nao entra na fila viva.
        var newcomer = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        postWalkin(newcomer, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_ACCEPTING_NEW"));

        // Intent de fila (#40, targetTime) TAMBEM nasce SCHEDULED na fila viva -> igualmente
        // barrado (senao prenderia a drenagem e o auto-OFF nunca chegaria).
        var scheduler = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        postWalkin(scheduler, OffsetDateTime.now().plusMinutes(120))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_ACCEPTING_NEW"));

        // "Agendar data futura" que segue livre = INTENTION (#4, fora da fila viva).
        CreateIntentionRequest intentionRequest = new CreateIntentionRequest(
                UUID.fromString(professionalId), serviceItemIds, OffsetDateTime.now().plusMinutes(120));
        var intentionResult = mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", scheduler.token())
                        .content(mapper.writeValueAsString(intentionRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // Cancela a INTENTION — nao vaza para os casos seguintes nem para outras classes.
        String intentionId = new JSONObject(intentionResult.getResponse().getContentAsString()).getString("id");
        mvc.perform(delete("/attendances/intentions/" + intentionId)
                        .header("Authorization", scheduler.token()))
                .andExpect(status().isNoContent());

        // Snapshot da fila expoe a flag.
        mvc.perform(get("/establishment/id/" + establishmentId + "/professionals-with-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[?(@.name == 'Prof Drain')].accepting_new").value(false));
    }

    @Test
    @Order(30)
    @DisplayName("drenagem: finish do ULTIMO atendimento com fila fechada -> auto-OFF e loja fecha")
    void drainEnd_autoOffClosesShop() throws Exception {
        // Atende o unico da fila (start -> finish).
        mvc.perform(patch("/attendances/" + queuedAttendanceId + "/start")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/" + queuedAttendanceId + "/finish")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        // Drenagem concluida: prof OFF sozinho; ultimo presente -> loja CLOSED (derivacao).
        mvc.perform(get("/professional/me")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.work_status").value("OFF"))
                .andExpect(jsonPath("$.accepting_new").value(false));
        assertOpenStatus("CLOSED");
    }

    @Test
    @Order(40)
    @DisplayName("turno novo: voltar de OFF para WORKING reabre a fila (accepting_new=true)")
    void backToWork_reopensQueue() throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepting_new").value(true));
        assertOpenStatus("OPEN");
    }

    @Test
    @Order(50)
    @DisplayName("fechar fila SEM nada ativo: auto-OFF imediato (drena vazio e sai na hora)")
    void closeEmptyQueue_immediateAutoOff() throws Exception {
        patchAcceptingNew(false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepting_new").value(false))
                .andExpect(jsonPath("$.work_status").value("OFF")); // nada ativo -> ja sai
        assertOpenStatus("CLOSED");
    }

    @Test
    @Order(60)
    @DisplayName("reabrir manualmente (value=true) e idempotente e volta a aceitar")
    void reopenManually_acceptsAgain() throws Exception {
        patchStatus("WORKING"); // turno novo (ja reabriria sozinho); reabre tambem na mao
        patchAcceptingNew(true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepting_new").value(true));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var result = postWalkin(client, null)
                .andExpect(status().isCreated())
                .andReturn();

        // Limpa: finaliza o atendimento e sai (deixa o mundo OFF/CLOSED p/ outras classes).
        String attId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        mvc.perform(patch("/attendances/" + attId + "/start").header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/" + attId + "/finish").header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        patchStatus("OFF");
    }
}
