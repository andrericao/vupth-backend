package com.ospaulos.vupth.integrations.client;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** pagar-multa-avulso (DEC-83): o Pix da multa e do app; as multas pagas nao voltam a ser cobradas. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"vupth.app-pix.key=+5511988887777", "vupth.app-pix.holder-name=VUPTH APP"})
class PenaltyPaymentIntegrationTest {

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

    @Test
    @DisplayName("paga avulso: nada a pagar 409; gera o Pix do app com tudo; nao cobra de novo; o painel confirma; vencido volta a pendente")
    void flow() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID clientId = UUID.fromString(client.id());

        mvc.perform(post("/clients/me/penalties/pay").header("Authorization", client.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PENALTY_NOTHING_TO_PAY"));

        penalty(clientId, "5.00");
        penalty(clientId, "3.00");
        mvc.perform(get("/clients/me/pending-penalties").header("Authorization", client.token()))
                .andExpect(jsonPath("$.total").value(8.00));

        JsonNode payment = mapper.readTree(mvc.perform(post("/clients/me/penalties/pay")
                        .header("Authorization", client.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(8.00))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.holder_name").value("VUPTH APP"))
                .andExpect(jsonPath("$.pix_copy_paste").value(org.hamcrest.Matchers.startsWith("000201")))
                .andReturn().getResponse().getContentAsString());
        String id = payment.get("id").asText();

        // sem cobrar duas vezes: as multas estao no pagamento, nao ficam pendentes; pedir de novo devolve o mesmo
        mvc.perform(get("/clients/me/pending-penalties").header("Authorization", client.token()))
                .andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/clients/me/penalties/pay").header("Authorization", client.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id));
        // uma multa nova gera outro pagamento so com ela
        penalty(clientId, "5.00");
        mvc.perform(post("/clients/me/penalties/pay").header("Authorization", client.token()))
                .andExpect(jsonPath("$.amount").value(5.00))
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(id)));

        // so o dono do pagamento o consulta
        var stranger = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/clients/me/penalties/payments/{id}", id).header("Authorization", stranger.token()))
                .andExpect(status().isNotFound());

        // o painel confirma
        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        mvc.perform(get("/admin/penalty-payments").header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].client_id").value(client.id()));
        mvc.perform(post("/admin/penalty-payments/{id}/paid", id).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.pix_copy_paste").doesNotExist());
        mvc.perform(post("/admin/penalty-payments/{id}/paid", id).header("Authorization", admin.token()))
                .andExpect(status().isOk());
        mvc.perform(get("/clients/me/penalties/payments/{id}", id).header("Authorization", client.token()))
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(post("/admin/penalty-payments/{id}/paid", UUID.randomUUID()).header("Authorization", admin.token()))
                .andExpect(status().isNotFound());

        // o pagamento da multa de 5 vence: as multas voltam a pendentes
        jdbc.update("UPDATE penalty_payment SET expires_at = now() - interval '1 hour' WHERE client_id = ? AND status = 'PENDING'",
                clientId);
        mvc.perform(get("/clients/me/pending-penalties").header("Authorization", client.token()))
                .andExpect(jsonPath("$.total").value(5.00));
        assertThat(penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(clientId)).hasSize(3);
    }

    @Test
    @DisplayName("so cliente paga multa; quem nao e CLIENT leva 403")
    void clientsOnly() throws Exception {
        mvc.perform(post("/clients/me/penalties/pay")).andExpect(status().isUnauthorized());
    }
}
