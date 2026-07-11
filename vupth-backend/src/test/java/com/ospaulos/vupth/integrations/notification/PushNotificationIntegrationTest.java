package com.ospaulos.vupth.integrations.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.notification.dto.request.DeviceTokenRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.push.PushSender;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — Notificacoes push (backlog #1).
 *
 * Cobre (a) registro/remocao de device token via {@code POST/DELETE /devices} e (b) o disparo de push:
 * "voce e o proximo" quando o cliente chega a frente da fila (deduplicado), e os avisos pessoais
 * ATTENDANCE_STARTED / ATTENDANCE_FINISHED. O {@link PushSender} e mockado (provider=none no perfil de
 * teste) e verificamos as chamadas por token + titulo.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushNotificationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired UserRepository userRepository;

    /** Mock do sender: NoOpPushSender (provider=none) e substituido por este mock. */
    @MockBean PushSender pushSender;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");

        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private void registerDevice(String userToken, String token, String platform) throws Exception {
        mvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", userToken)
                        .content(mapper.writeValueAsString(new DeviceTokenRequest(token, platform))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void clearQueue() {
        attendanceRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        reset(pushSender);
    }

    // ─────────────────────────────────────────────────────────────
    // Device token registration
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /devices registra token (upsert idempotente) e DELETE remove")
    void device_registerUpsertAndDelete() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";

        registerDevice(client.token(), token, "android");
        assertThat(deviceTokenRepository.findByToken(token)).isPresent();
        assertThat(deviceTokenRepository.findByUserId(UUID.fromString(client.id()))).hasSize(1);

        // re-registrar o mesmo token (upsert) — continua 1 registro, plataforma atualizada
        registerDevice(client.token(), token, "ios");
        assertThat(deviceTokenRepository.findByUserId(UUID.fromString(client.id()))).hasSize(1);
        assertThat(deviceTokenRepository.findByToken(token).orElseThrow().getPlatform()).isEqualTo("ios");

        mvc.perform(delete("/devices/{token}", token)
                        .header("Authorization", client.token()))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.findByToken(token)).isEmpty();
    }

    @Test
    @Order(11)
    @DisplayName("POST /devices sem token -> 401; token em branco -> 422")
    void device_authAndValidation() throws Exception {
        String token = "ExponentPushToken[" + UUID.randomUUID() + "]";
        mvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new DeviceTokenRequest(token, "android"))))
                .andExpect(status().isUnauthorized());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(new DeviceTokenRequest("  ", "android"))))
                .andExpect(status().isUnprocessableEntity());
    }

    // ─────────────────────────────────────────────────────────────
    // Push firing
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("Entrar na fila vazia dispara push 'voce e o proximo' ao 1o da fila")
    void push_nextInLine_onJoinEmptyQueue() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String t1 = "ExponentPushToken[" + UUID.randomUUID() + "]";
        registerDevice(client.token(), t1, "android");

        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId),
                UUID.fromString(client.id()), serviceItemIds, AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        verify(pushSender).send(argThat(l -> l.contains(t1)), eq("É quase a sua vez!"), any(), any());
    }

    @Test
    @Order(21)
    @DisplayName("2o da fila NAO recebe 'proximo'; ao 1o iniciar, 2o vira proximo (dedup + avanco)")
    void push_nextInLine_dedupAndAdvance() throws Exception {
        // client1 entra (vira frente) — com token
        var c1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String t1 = "ExponentPushToken[" + UUID.randomUUID() + "]";
        registerDevice(c1.token(), t1, "android");
        String att1 = join(c1);
        verify(pushSender).send(argThat(l -> l.contains(t1)), eq("É quase a sua vez!"), any(), any());
        assertThat(att1).isNotNull();

        // client2 entra atras — token t2; NAO deve receber 'proximo' ainda
        clearInvocations(pushSender);
        var c2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String t2 = "ExponentPushToken[" + UUID.randomUUID() + "]";
        registerDevice(c2.token(), t2, "ios");
        join(c2);
        verify(pushSender, never()).send(any(), eq("É quase a sua vez!"), any(), any());

        // profissional inicia client1 -> STARTED para t1 + 'proximo' para t2 (agora frente)
        clearInvocations(pushSender);
        mvc.perform(patch("/attendances/{id}/start", att1)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        verify(pushSender).send(argThat(l -> l.contains(t1)), eq("É a sua vez!"), any(), any());
        verify(pushSender).send(argThat(l -> l.contains(t2)), eq("É quase a sua vez!"), any(), any());

        // finish -> FINISHED para t1
        clearInvocations(pushSender);
        mvc.perform(patch("/attendances/{id}/finish", att1)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());
        verify(pushSender).send(argThat(l -> l.contains(t1)), eq("Atendimento concluído"), any(), any());
    }

    @Test
    @Order(22)
    @DisplayName("(BUG9) profissional remove da fila -> push ATTENDANCE_CANCELLED + status CANCELLED")
    void push_attendanceCancelled_onRemoveFromQueue() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String t1 = "ExponentPushToken[" + UUID.randomUUID() + "]";
        registerDevice(client.token(), t1, "android");
        String att = join(client);

        // isola o push de "voce e o proximo" disparado ao entrar na fila vazia
        clearInvocations(pushSender);

        mvc.perform(delete("/professional-queue/{id}/remove", att)
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        // o cliente recebe o aviso pessoal para sair do estado "voce esta na fila"
        verify(pushSender).send(argThat(l -> l.contains(t1)), eq("Você saiu da fila"), any(), any());
        // e o atendimento sai do read ativo do cliente (CANCELLED)
        assertThat(attendanceRepository.findById(UUID.fromString(att)).orElseThrow().getStatus())
                .isEqualTo(AttendanceStatus.CANCELLED);
    }

    private String join(com.ospaulos.vupth.integrations.utils.TestUserUtils.UserAuthAndToken client) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId),
                UUID.fromString(client.id()), serviceItemIds, AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        deviceTokenRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
