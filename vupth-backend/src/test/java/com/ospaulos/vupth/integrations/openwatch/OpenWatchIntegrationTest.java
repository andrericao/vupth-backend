package com.ospaulos.vupth.integrations.openwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.openwatch.repository.OpenWatchRepository;
import com.ospaulos.vupth.domain.openwatch.service.OpenWatchService;
import com.ospaulos.vupth.domain.professional.event.WorkStatusChangedEvent;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** avisar-quando-abrir (BAK-8, DEC-160): o contrato das rotas e o disparo do aviso de abertura. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenWatchIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OpenWatchService service;
    @Autowired OpenWatchRepository repository;
    @MockBean PushNotificationPublisher pushPublisher;

    private final Faker faker = new Faker();

    private String body(String establishmentId, String professionalId, String kind) {
        return "{\"establishment_id\":\"" + establishmentId + "\","
                + (professionalId != null ? "\"professional_id\":\"" + professionalId + "\"," : "")
                + "\"kind\":\"" + kind + "\"}";
    }

    @Test
    @DisplayName("pede, repete (200, o mesmo), lista os de hoje, o aviso sai uma vez quando o profissional entra em expediente, cancela")
    void fullCycle() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token()).id();
        var worker = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, worker.token(), establishmentId, "Beto");
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        String created = mvc.perform(post("/open-watches").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(establishmentId, worker.id(), "NOTIFY_OPEN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("NOTIFY_OPEN"))
                .andExpect(jsonPath("$.professional_id").value(worker.id()))
                .andExpect(jsonPath("$.fired_at").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String watchId = new JSONObject(created).getString("id");

        mvc.perform(post("/open-watches").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(establishmentId, worker.id(), "NOTIFY_OPEN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(watchId));

        mvc.perform(get("/open-watches/me").header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        UUID est = UUID.fromString(establishmentId);
        UUID prof = UUID.fromString(worker.id());
        // intervalo -> trabalhando nao e abertura: nada sai
        service.onWorkStatusChanged(new WorkStatusChangedEvent(prof, est,
                ProfessionalStatus.ON_BREAK, ProfessionalStatus.WORKING, OffsetDateTime.now()));
        verify(pushPublisher, never()).notifyUser(eq(UUID.fromString(client.id())), any(), any(), anyMap());

        service.onWorkStatusChanged(new WorkStatusChangedEvent(prof, est,
                ProfessionalStatus.OFF, ProfessionalStatus.WORKING, OffsetDateTime.now()));
        verify(pushPublisher).notifyUser(eq(UUID.fromString(client.id())), eq("Beto começou a atender"), any(), anyMap());
        assertThat(repository.findById(UUID.fromString(watchId)).orElseThrow().getFiredAt()).isNotNull();

        // dispara uma vez so
        service.onWorkStatusChanged(new WorkStatusChangedEvent(prof, est,
                ProfessionalStatus.OFF, ProfessionalStatus.WORKING, OffsetDateTime.now()));
        verify(pushPublisher).notifyUser(eq(UUID.fromString(client.id())), eq("Beto começou a atender"), any(), anyMap());

        // de outro usuario: 404 sem revelar
        mvc.perform(delete("/open-watches/{id}", watchId).header("Authorization", owner.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("OPEN_WATCH_NOT_FOUND"));
        mvc.perform(delete("/open-watches/{id}", watchId).header("Authorization", client.token()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/open-watches/me").header("Authorization", client.token()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("erros: sem login 401, loja inexistente 404, profissional de outra loja 422, sem kind 422")
    void errors() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token()).id();

        mvc.perform(post("/open-watches").contentType(MediaType.APPLICATION_JSON)
                        .content(body(establishmentId, null, "NOTIFY_OPEN")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/open-watches").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID().toString(), null, "NOTIFY_OPEN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOT_FOUND"));
        mvc.perform(post("/open-watches").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(establishmentId, client.id(), "QUEUE_MOVING")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPEN_WATCH_PROFESSIONAL_INVALID"));
        mvc.perform(post("/open-watches").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establishment_id\":\"" + establishmentId + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("pedido da loja (sem profissional) cobre o primeiro que entrar; QUEUE_MOVING nao dispara na abertura")
    void establishmentWide() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token()).id();
        var worker = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, worker.token(), establishmentId, "Ana");
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        for (String kind : new String[]{"NOTIFY_OPEN", "QUEUE_MOVING"}) {
            mvc.perform(post("/open-watches").header("Authorization", client.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(establishmentId, null, kind)))
                    .andExpect(status().isCreated());
        }
        service.onWorkStatusChanged(new WorkStatusChangedEvent(UUID.fromString(worker.id()),
                UUID.fromString(establishmentId), ProfessionalStatus.OFF, ProfessionalStatus.WORKING, OffsetDateTime.now()));
        verify(pushPublisher).notifyUser(eq(UUID.fromString(client.id())), eq("Ana começou a atender"), any(), anyMap());
        verify(pushPublisher, never()).notifyUser(eq(UUID.fromString(client.id())), eq("A fila começou a andar"), any(), anyMap());
    }
}
