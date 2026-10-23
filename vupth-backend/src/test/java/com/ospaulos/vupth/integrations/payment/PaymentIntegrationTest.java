package com.ospaulos.vupth.integrations.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
import com.ospaulos.vupth.api.attendance.dto.request.CutRequestRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — pagamento por Pix (pagamento-pix, contrato 2a2b0ec + adendos de 10/10).
 *
 * <p>Uma loja de um dono que tambem atende; o cliente entra na fila, diz que pagou ainda na fila,
 * e finaliza sem o "Sim" (fica devendo, o portao barra); o profissional confirma; outro atendimento
 * termina com o "Sim" do modal.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PaymentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    String profToken, profId, establishmentId;
    String clientToken, clientId;
    String strangerToken;
    List<UUID> serviceItemIds;
    String firstId, secondId;
    double price;

    @BeforeAll
    void setup() throws Exception {
        var prof = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        profId = prof.id();
        profToken = prof.token();
        establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Beto");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        var item = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        serviceItemIds = List.of(UUID.fromString(item.id()));
        price = item.price().doubleValue();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        clientId = client.id();
        clientToken = client.token();
        strangerToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
    }

    private String enterQueue() throws Exception {
        AttendanceRequest request = new AttendanceRequest(UUID.fromString(profId), UUID.fromString(establishmentId),
                UUID.fromString(clientId), serviceItemIds, AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(5), 30, OffsetDateTime.now());
        String body = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return new JSONObject(body).getString("id");
    }

    @Test
    @Order(1)
    @DisplayName("Chave Pix: sem chave 404; chave que nao bate 422; salva normalizada; GET devolve")
    void pixKey() throws Exception {
        mvc.perform(get("/professional/me/pix").header("Authorization", profToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PIX_KEY_NOT_SET"));
        mvc.perform(put("/professional/me/pix").header("Authorization", profToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_type\":\"CPF\",\"key\":\"123.456.789-00\",\"holder_name\":\"Beto\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PIX_KEY_INVALID"));
        mvc.perform(put("/professional/me/pix").header("Authorization", profToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key_type\":\"PHONE\",\"key\":\"(11) 98765-4321\",\"holder_name\":\"Beto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("+5511987654321"));
        mvc.perform(get("/professional/me/pix").header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key_type").value("PHONE"))
                .andExpect(jsonPath("$.holder_name").value("Beto"));
        mvc.perform(get("/professional/me/pix").header("Authorization", clientToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(2)
    @DisplayName("Na fila o pagamento ja existe: o cliente ve o valor e o copia-e-cola; quem nao e parte, 403")
    void paymentOpensOnEntry() throws Exception {
        firstId = enterQueue();
        mvc.perform(get("/attendances/{id}/payment", firstId).header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(price))
                .andExpect(jsonPath("$.services_value").value(price))
                .andExpect(jsonPath("$.penalties_value").value(0.0))
                .andExpect(jsonPath("$.pix_copy_paste", startsWith("000201")))
                .andExpect(jsonPath("$.pix_key").value("+5511987654321"))
                .andExpect(jsonPath("$.key_type").value("PHONE"));
        // pix-key-no-pagamento: a chave em texto e so do cliente do atendimento
        mvc.perform(get("/attendances/{id}/payment", firstId).header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pix_key").value(nullValue()));
        mvc.perform(get("/attendances/{id}/payment", firstId).header("Authorization", strangerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PAYMENT_ACCESS_DENIED"));
    }

    @Test
    @Order(3)
    @DisplayName("Ja paguei ainda na fila: so o cliente; de novo da 409; o dot do profissional conta 1")
    void claimInQueue() throws Exception {
        mvc.perform(post("/attendances/{id}/payment/claim", firstId).header("Authorization", strangerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/attendances/{id}/payment/claim", firstId).header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.claimed_at", notNullValue()));
        mvc.perform(post("/attendances/{id}/payment/claim", firstId).header("Authorization", clientToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATUS"));
        mvc.perform(get("/professional/me/pending-payments").header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaiting_count").value(1))
                .andExpect(jsonPath("$.items[0].attendance_id").value(firstId));
    }

    @Test
    @Order(4)
    @DisplayName("Finalizou sem o Sim: fica devendo; fila, agenda e pedido de corte dao 409 PAYMENT_PENDING")
    void finishWithoutPaymentBlocksClient() throws Exception {
        mvc.perform(patch("/attendances/{id}/start", firstId).header("Authorization", profToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", firstId).header("Authorization", profToken))
                .andExpect(status().isOk())
                // finish-com-payment-status (S47): a fila volta com o pagamento de quem terminou
                // o cliente ja tinha tocado "Ja paguei" (teste 3): segue aguardando a confirmacao
                .andExpect(jsonPath("$.payment_status").value("AWAITING_CONFIRMATION"));

        mvc.perform(get("/clients/me/pending-payments").header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.total").value(price))
                .andExpect(jsonPath("$.items[0].status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.items[0].professional_name", notNullValue()));

        AttendanceRequest queue = new AttendanceRequest(UUID.fromString(profId), UUID.fromString(establishmentId),
                UUID.fromString(clientId), serviceItemIds, AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(5), 30, OffsetDateTime.now());
        mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken).content(mapper.writeValueAsString(queue)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.items[0].attendance_id").value(firstId));
        mvc.perform(post("/attendances/intentions").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CreateIntentionRequest(
                                UUID.fromString(profId), serviceItemIds, OffsetDateTime.now().plusDays(1)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_PENDING"));
        mvc.perform(post("/attendances/cut-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CutRequestRequest(
                                UUID.fromString(profId), UUID.fromString(establishmentId), serviceItemIds))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_PENDING"));
    }

    @Test
    @Order(5)
    @DisplayName("Recebi: o dono confirma; de novo 409; o cliente fica sem pendencia e o atendimento mostra PAID")
    void confirmReleasesClient() throws Exception {
        mvc.perform(post("/attendances/{id}/payment/confirm", firstId).header("Authorization", clientToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/attendances/{id}/payment/confirm", firstId).header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.confirmed_at", notNullValue()));
        mvc.perform(post("/attendances/{id}/payment/confirm", firstId).header("Authorization", profToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATUS"));
        mvc.perform(get("/clients/me/pending-payments").header("Authorization", clientToken))
                .andExpect(jsonPath("$.count").value(0));
        mvc.perform(get("/attendances/{id}", firstId).header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment_status").value("PAID"));
        mvc.perform(get("/professional/me/pending-payments").header("Authorization", profToken))
                .andExpect(jsonPath("$.awaiting_count").value(0));
    }

    @Test
    @Order(6)
    @DisplayName("O Sim do modal: finish com payment_received termina pago")
    void finishWithPaymentReceived() throws Exception {
        secondId = enterQueue();
        mvc.perform(patch("/attendances/{id}/start", secondId).header("Authorization", profToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", secondId).header("Authorization", profToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"payment_received\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment_status").value("PAID"));
        mvc.perform(get("/attendances/{id}/payment", secondId).header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mvc.perform(get("/clients/me/pending-payments").header("Authorization", clientToken))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @Order(7)
    @DisplayName("Sem chave: o pagamento mostra so o valor; DELETE da 204")
    void withoutKeyShowsOnlyAmount() throws Exception {
        mvc.perform(delete("/professional/me/pix").header("Authorization", profToken))
                .andExpect(status().isNoContent());
        mvc.perform(get("/attendances/{id}/payment", secondId).header("Authorization", clientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pix_copy_paste").value(nullValue()))
                .andExpect(jsonPath("$.pix_key").value(nullValue()))
                .andExpect(jsonPath("$.holder_name").value(nullValue()))
                .andExpect(jsonPath("$.amount").value(price));
    }
}
