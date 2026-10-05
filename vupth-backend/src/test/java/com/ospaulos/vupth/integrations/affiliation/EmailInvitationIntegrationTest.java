package com.ospaulos.vupth.integrations.affiliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.affiliation.repository.EmailInvitationRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
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

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * convite-sem-conta (Partner S30): o dono convida pelo e-mail quem ainda nao tem conta; o convite
 * vira o convite comum quando a pessoa cria o perfil profissional com esse e-mail.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailInvitationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired AffiliationRequestRepository affiliationRepository;
    @Autowired EmailInvitationRepository emailInvitationRepository;

    private final Faker faker = new Faker();

    String ownerToken, establishmentId, otherOwnerToken;
    final String newcomerEmail = "novato." + UUID.randomUUID().toString().substring(0, 8) + "@vupth.test";
    String emailInvitationId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();

        var otherOwner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.otherOwnerToken = otherOwner.token();
        TestUserUtils.becomeOwner(mvc, mapper, faker, otherOwnerToken);
    }

    private MvcResult invite(String token, String email) throws Exception {
        return mvc.perform(post("/establishment/{id}/email-invitations", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"email\":\"" + email + "\"}"))
                .andReturn();
    }

    @Test
    @Order(10)
    @DisplayName("e-mail sem conta -> 201 WAITING_ACCOUNT (normalizado) e aparece na lista do dono")
    void invite_unknownEmail_waitsForAccount() throws Exception {
        MvcResult r = invite(ownerToken, "  " + newcomerEmail.toUpperCase() + " ");
        Assertions.assertEquals(201, r.getResponse().getStatus());
        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        Assertions.assertEquals("WAITING_ACCOUNT", body.getString("outcome"));
        JSONObject inv = body.getJSONObject("emailInvitation");
        Assertions.assertEquals(newcomerEmail, inv.getString("email"));
        Assertions.assertEquals("PENDING", inv.getString("status"));
        this.emailInvitationId = inv.getString("id");

        mvc.perform(get("/establishment/{id}/email-invitations", establishmentId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(emailInvitationId));
    }

    @Test
    @Order(11)
    @DisplayName("convidar o mesmo e-mail de novo e idempotente (o mesmo convite)")
    void invite_again_isIdempotent() throws Exception {
        MvcResult r = invite(ownerToken, newcomerEmail);
        Assertions.assertEquals(201, r.getResponse().getStatus());
        Assertions.assertEquals(emailInvitationId,
                new JSONObject(r.getResponse().getContentAsString()).getJSONObject("emailInvitation").getString("id"));
    }

    @Test
    @Order(12)
    @DisplayName("dono de outra loja -> 403; e-mail invalido -> 422 VALIDATION_ERROR")
    void invite_otherOwner403_invalidEmail422() throws Exception {
        Assertions.assertEquals(403, invite(otherOwnerToken, "x@vupth.test").getResponse().getStatus());
        Assertions.assertEquals(422, invite(ownerToken, "nao-e-email").getResponse().getStatus());
        mvc.perform(get("/establishment/{id}/email-invitations", establishmentId)
                        .header("Authorization", otherOwnerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(20)
    @DisplayName("a pessoa cria conta e perfil profissional com o e-mail -> o convite vira convite comum PENDING")
    void register_convertsIntoInvitation() throws Exception {
        String password = "Teste@123";
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UserRegisterRequest(newcomerEmail, password))))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(newcomerEmail, password))))
                .andExpect(status().isOk())
                .andReturn();
        String token = "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");

        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new ProfessionalRegisterRequest("Novato", "Corte"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/professional/me/affiliations").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].establishmentId").value(establishmentId))
                .andExpect(jsonPath("$[0].type").value("INVITATION"))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        // saiu da lista dos que esperam conta
        mvc.perform(get("/establishment/{id}/email-invitations", establishmentId)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @Order(30)
    @DisplayName("e-mail que ja e de um profissional -> 201 INVITATION_SENT (o convite comum na hora)")
    void invite_existingProfessional_sendsInvitation() throws Exception {
        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", pro.token())
                        .content(mapper.writeValueAsString(new ProfessionalRegisterRequest("Ja Pro", "Corte"))))
                .andExpect(status().isCreated());

        MvcResult r = invite(ownerToken, pro.email());
        Assertions.assertEquals(201, r.getResponse().getStatus());
        JSONObject body = new JSONObject(r.getResponse().getContentAsString());
        Assertions.assertEquals("INVITATION_SENT", body.getString("outcome"));
        Assertions.assertEquals("INVITATION", body.getJSONObject("affiliation").getString("type"));
    }

    @Test
    @Order(40)
    @DisplayName("DELETE /email-invitations/{id} -> o dono cancela; outro dono -> 403")
    void cancel_byOwner() throws Exception {
        String email = "cancelar." + UUID.randomUUID().toString().substring(0, 8) + "@vupth.test";
        String id = new JSONObject(invite(ownerToken, email).getResponse().getContentAsString())
                .getJSONObject("emailInvitation").getString("id");

        mvc.perform(delete("/email-invitations/{id}", id).header("Authorization", otherOwnerToken))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/email-invitations/{id}", id).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @Order(41)
    @DisplayName("DELETE /email-invitations/{id} -> ja cancelado 400; id que nao existe 404 AFFILIATION_NOT_FOUND")
    void cancel_twice400_unknown404() throws Exception {
        String email = "duas.vezes." + UUID.randomUUID().toString().substring(0, 8) + "@vupth.test";
        String id = new JSONObject(invite(ownerToken, email).getResponse().getContentAsString())
                .getJSONObject("emailInvitation").getString("id");

        mvc.perform(delete("/email-invitations/{id}", id).header("Authorization", ownerToken))
                .andExpect(status().isOk());
        mvc.perform(delete("/email-invitations/{id}", id).header("Authorization", ownerToken))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/email-invitations/{id}", UUID.randomUUID()).header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AFFILIATION_NOT_FOUND"));
    }

    @AfterAll
    void tearDown() {
        emailInvitationRepository.deleteAll();
        affiliationRepository.deleteAll();
        professionalRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
