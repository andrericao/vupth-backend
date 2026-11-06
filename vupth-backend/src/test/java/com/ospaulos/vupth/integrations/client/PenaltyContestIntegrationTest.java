package com.ospaulos.vupth.integrations.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** multa-contestavel (F5): o cliente contesta em 7 dias, a multa sai do total; o ADMIN mantem ou anula. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PenaltyContestIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired JdbcTemplate jdbc;

    private final Faker faker = new Faker();

    private UUID penalty(UUID clientId, String amount) {
        var client = clientRepository.findById(clientId).orElseThrow();
        return penaltyEventRepository
                .save(new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, null, 0, new BigDecimal(amount))).getId();
    }

    private String body(String key, String value) {
        return "{\"" + key + "\":\"" + value + "\"}";
    }

    @Test
    @DisplayName("contestar: sai do total; 2a vez 409; motivo vazio 422; de outro cliente 404; vencido 409; o admin mantem (volta) ou anula")
    void flow() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID clientId = UUID.fromString(client.id());
        UUID kept = penalty(clientId, "5.00");
        UUID voided = penalty(clientId, "3.00");
        UUID old = penalty(clientId, "2.00");
        jdbc.update("UPDATE client_penalty_event SET created_at = now() - interval '8 days' WHERE id = ?", old);

        mvc.perform(post("/clients/me/penalties/{id}/contest", kept).header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", " ")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/clients/me/penalties/{id}/contest", old).header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", "nao atrasei")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_CONTEST_EXPIRED"));

        var stranger = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/clients/me/penalties/{id}/contest", kept).header("Authorization", stranger.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", "x")))
                .andExpect(status().isNotFound());

        mvc.perform(post("/clients/me/penalties/{id}/contest", kept).header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", "O onibus quebrou")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.penalty_id").value(kept.toString()))
                .andExpect(jsonPath("$.reason").value("O onibus quebrou"));
        mvc.perform(post("/clients/me/penalties/{id}/contest", voided).header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", "Cheguei no horario")))
                .andExpect(status().isOk());
        mvc.perform(post("/clients/me/penalties/{id}/contest", kept).header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", "de novo")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_ALREADY_CONTESTED"));

        // contestadas saem do total a pagar (so a de 2.00, vencida para contestar, segue devida)
        mvc.perform(get("/clients/me/pending-penalties").header("Authorization", client.token()))
                .andExpect(jsonPath("$.total").value(2.00));

        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        mvc.perform(get("/admin/multas-contestadas").header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + kept + "')].contest_reason").value("O onibus quebrou"));
        mvc.perform(post("/admin/multas-contestadas/{id}/resolve", kept).header("Authorization", admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("decision", "TALVEZ")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/multas-contestadas/{id}/resolve", kept).header("Authorization", admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("decision", "UPHOLD")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("UPHELD"));
        mvc.perform(post("/admin/multas-contestadas/{id}/resolve", voided).header("Authorization", admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("decision", "VOID")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolution").value("VOIDED"));
        mvc.perform(post("/admin/multas-contestadas/{id}/resolve", voided).header("Authorization", admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("decision", "UPHOLD")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_ALREADY_RESOLVED"));
        mvc.perform(post("/admin/multas-contestadas/{id}/resolve", old).header("Authorization", admin.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("decision", "VOID")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_NOT_CONTESTED"));

        // mantida (5.00) volta ao total; anulada (3.00) nunca mais volta
        mvc.perform(get("/clients/me/pending-penalties").header("Authorization", client.token()))
                .andExpect(jsonPath("$.total").value(7.00));
        mvc.perform(get("/admin/multas-contestadas").header("Authorization", admin.token()))
                .andExpect(jsonPath("$.items[?(@.id=='" + kept + "')]").isEmpty());
        mvc.perform(post("/clients/me/penalties/{id}/contest", kept).header("Authorization", stranger.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body("reason", "x")))
                .andExpect(status().isNotFound());
    }
}
