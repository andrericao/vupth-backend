package com.ospaulos.vupth.integrations.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** limite-de-duas-multas (DEC-83): com 2 multas devidas o cliente so marca depois de quitar (409 PENALTY_LIMIT_REACHED). */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"vupth.app-pix.key=+5511988887777", "vupth.payment.mode=SPLIT_PIX"})
class PenaltyLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired JdbcTemplate jdbc;

    private final Faker faker = new Faker();

    private void penalty(UUID clientId, String amount) {
        var client = clientRepository.findById(clientId).orElseThrow();
        penaltyEventRepository.save(new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, null, 0, new BigDecimal(amount)));
    }

    /** Tenta entrar na fila com uma loja que nao existe: o portao das multas vem antes de qualquer outra regra. */
    private ResultActions enter(String token) throws Exception {
        AttendanceRequest request = new AttendanceRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        return mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token).content(mapper.writeValueAsString(request)));
    }

    private void assertNotBlocked(ResultActions r) throws Exception {
        String body = r.andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("PENALTY_LIMIT_REACHED");
    }

    @Test
    @DisplayName("0 e 1 multa passam do portao; 2 barram com o corpo; o Pix vigente conta; a confirmacao libera; o vencido volta a contar")
    void limit() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID clientId = UUID.fromString(client.id());

        assertNotBlocked(enter(client.token()));
        penalty(clientId, "5.00");
        assertNotBlocked(enter(client.token()));

        penalty(clientId, "3.00");
        enter(client.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_LIMIT_REACHED"))
                .andExpect(jsonPath("$.limit").value(2))
                .andExpect(jsonPath("$.total").value(8.00))
                .andExpect(jsonPath("$.penalties.length()").value(2))
                .andExpect(jsonPath("$.payment_id").doesNotExist());

        // os outros dois portoes (agendar e pedir corte) barram igual
        mvc.perform(post("/attendances/intentions").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(new CreateIntentionRequest(
                                UUID.randomUUID(), List.of(UUID.randomUUID()), OffsetDateTime.now().plusDays(1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_LIMIT_REACHED"));
        mvc.perform(post("/attendances/cut-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(new CutRequestRequest(
                                UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID())))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_LIMIT_REACHED"));

        // gerar o Pix nao libera: o pagamento vigente conta, e o corpo traz o pagamento para reabrir o Pix
        JsonNode payment = mapper.readTree(mvc.perform(post("/clients/me/penalties/pay")
                        .header("Authorization", client.token()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String paymentId = payment.get("id").asText();
        enter(client.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_LIMIT_REACHED"))
                .andExpect(jsonPath("$.total").value(8.00))
                .andExpect(jsonPath("$.payment_id").value(paymentId));

        // o painel confirma: libera na hora
        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        mvc.perform(post("/admin/penalty-payments/{id}/paid", paymentId).header("Authorization", admin.token()))
                .andExpect(status().isOk());
        assertNotBlocked(enter(client.token()));
    }

    @Test
    @DisplayName("pagamento vencido sem confirmacao: as multas voltam a pendentes e continuam contando")
    void expiredStillCounts() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID clientId = UUID.fromString(client.id());
        penalty(clientId, "5.00");
        penalty(clientId, "5.00");
        mvc.perform(post("/clients/me/penalties/pay").header("Authorization", client.token()))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE penalty_payment SET expires_at = now() - interval '1 hour' WHERE client_id = ?", clientId);

        enter(client.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_LIMIT_REACHED"))
                .andExpect(jsonPath("$.total").value(10.00))
                .andExpect(jsonPath("$.payment_id").doesNotExist());
    }
}
