package com.ospaulos.vupth.integrations.affiliation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.affiliation.dto.request.CreateAffiliationInvitationDTO;
import com.ospaulos.vupth.api.affiliation.dto.request.CreateAffiliationRequestDTO;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.domain.affiliation.repository.AffiliationRequestRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import com.ospaulos.vupth.integrations.utils.TestUserUtils.EstablishmentData;
import com.ospaulos.vupth.integrations.utils.TestUserUtils.UserAuthAndToken;
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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AffiliationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired AffiliationRequestRepository affiliationRepository;

    private final Faker faker = new Faker();

    String owner1Token, estab1Id;
    String owner2Token, estab2Id;
    String profAToken, profAId;
    String profBToken, profBId;
    String profCToken, profCId;
    String profDToken, profDId;
    String clientToken;

    String requestAffiliationId;
    String invitationAffiliationId;
    String rejectAffiliationId;
    String cancelAffiliationId;

    @BeforeAll
    void setup() throws Exception {
        log.info("[Affiliation-IT] setup");

        var owner1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.owner1Token = owner1.token();
        EstablishmentData estab1 = TestUserUtils.becomeOwner(mvc, mapper, faker, owner1Token);
        this.estab1Id = estab1.id();

        var owner2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.owner2Token = owner2.token();
        EstablishmentData estab2 = TestUserUtils.becomeOwner(mvc, mapper, faker, owner2Token);
        this.estab2Id = estab2.id();

        var profA = registerProfessionalWithoutEstablishment("ProfA");
        this.profAToken = profA.token();
        this.profAId = profA.id();

        var profB = registerProfessionalWithoutEstablishment("ProfB");
        this.profBToken = profB.token();
        this.profBId = profB.id();

        var profC = registerProfessionalWithoutEstablishment("ProfC");
        this.profCToken = profC.token();
        this.profCId = profC.id();

        var profD = registerProfessionalWithoutEstablishment("ProfD");
        this.profDToken = profD.token();
        this.profDId = profD.id();

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();

        log.info("[Affiliation-IT] setup done — estab1={} estab2={} profA={} profB={} profC={} profD={}",
                estab1Id, estab2Id, profAId, profBId, profCId, profDId);
    }

    private UserAuthAndToken registerProfessionalWithoutEstablishment(String name) throws Exception {
        UserAuthAndToken user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token())
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest(name, "Corte"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.establishment_id").isEmpty());
        return user;
    }

    // ─────────────────────────────────────────────────────────────
    // REQUEST FLOW — profissional solicita afiliação
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("POST /professional/me/affiliations — 201 profA solicita afiliação ao estab1")
    void requestAffiliation_success() throws Exception {
        MvcResult result = mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab1Id)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.professionalId").value(profAId))
                .andExpect(jsonPath("$.establishmentId").value(estab1Id))
                .andExpect(jsonPath("$.type").value("REQUEST"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.resolvedAt").isEmpty())
                .andDo(print())
                .andReturn();

        this.requestAffiliationId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[Affiliation-IT] request created id={}", requestAffiliationId);
    }

    @Test
    @Order(11)
    @DisplayName("POST /professional/me/affiliations — 409 pedido já pendente")
    void requestAffiliation_duplicate_409() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab1Id)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ALREADY_PENDING"))
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("GET /professional/me/affiliations — 200 profA lista 1 pedido pendente")
    void listProfessionalAffiliations_success() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(get("/professional/me/affiliations")
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(requestAffiliationId))
                .andExpect(jsonPath("$[0].type").value("REQUEST"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("GET /establishment/{id}/affiliations — 200 owner1 vê o pedido pendente")
    void listEstablishmentAffiliations_success() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(get("/establishment/{id}/affiliations", estab1Id)
                        .header("Authorization", owner1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(requestAffiliationId))
                .andExpect(jsonPath("$[0].professionalId").value(profAId))
                .andExpect(jsonPath("$[0].type").value("REQUEST"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ACCESS DENIED — usando o REQUEST pendente do profA
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(14)
    @DisplayName("PATCH /affiliations/{id}/accept — 403 profA tenta aceitar próprio pedido (deve ser owner)")
    void acceptRequest_wrongUser_403() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(patch("/affiliations/{id}/accept", requestAffiliationId)
                        .header("Authorization", profAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ACCESS_DENIED"))
                .andDo(print());
    }

    @Test
    @Order(15)
    @DisplayName("PATCH /affiliations/{id}/reject — 403 profA tenta rejeitar próprio pedido")
    void rejectRequest_wrongUser_403() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(patch("/affiliations/{id}/reject", requestAffiliationId)
                        .header("Authorization", profAToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ACCESS_DENIED"))
                .andDo(print());
    }

    @Test
    @Order(16)
    @DisplayName("DELETE /affiliations/{id} — 403 owner1 tenta cancelar pedido do profA (deve ser profA)")
    void cancelRequest_wrongUser_403() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(delete("/affiliations/{id}", requestAffiliationId)
                        .header("Authorization", owner1Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ACCESS_DENIED"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ACCEPT REQUEST — owner1 aceita o pedido do profA
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(17)
    @DisplayName("PATCH /affiliations/{id}/accept — 200 owner1 aceita pedido → profA afiliado")
    void acceptRequest_success() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(patch("/affiliations/{id}/accept", requestAffiliationId)
                        .header("Authorization", owner1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(requestAffiliationId))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andDo(print());
    }

    @Test
    @Order(18)
    @DisplayName("GET /professional/me/affiliations — 200 profA lista vazia (nenhum pendente)")
    void listProfessionalAffiliations_emptyAfterAccept() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(get("/professional/me/affiliations")
                        .header("Authorization", profAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)))
                .andDo(print());
    }

    @Test
    @Order(19)
    @DisplayName("POST /professional/me/affiliations — 409 profA já afiliado após accept")
    void requestAffiliation_alreadyAffiliated_409() throws Exception {
        Assumptions.assumeTrue(requestAffiliationId != null);

        mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profAToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab2Id)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_ALREADY_AFFILIATED"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // INVITATION FLOW — owner2 convida profB
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("POST /establishment/{id}/affiliations — 201 owner2 convida profB")
    void inviteProfessional_success() throws Exception {
        MvcResult result = mvc.perform(post("/establishment/{id}/affiliations", estab2Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner2Token)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.fromString(profBId)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.professionalId").value(profBId))
                .andExpect(jsonPath("$.establishmentId").value(estab2Id))
                .andExpect(jsonPath("$.type").value("INVITATION"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.resolvedAt").isEmpty())
                .andDo(print())
                .andReturn();

        this.invitationAffiliationId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
        log.info("[Affiliation-IT] invitation created id={}", invitationAffiliationId);
    }

    @Test
    @Order(21)
    @DisplayName("POST /establishment/{id}/affiliations — 409 convite já pendente")
    void inviteProfessional_duplicate_409() throws Exception {
        Assumptions.assumeTrue(invitationAffiliationId != null);

        mvc.perform(post("/establishment/{id}/affiliations", estab2Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner2Token)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.fromString(profBId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ALREADY_PENDING"))
                .andDo(print());
    }

    @Test
    @Order(22)
    @DisplayName("GET /establishment/{id}/affiliations — 200 owner2 lista 1 convite pendente")
    void listEstablishmentAffiliations_invitation() throws Exception {
        Assumptions.assumeTrue(invitationAffiliationId != null);

        mvc.perform(get("/establishment/{id}/affiliations", estab2Id)
                        .header("Authorization", owner2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].type").value("INVITATION"))
                .andExpect(jsonPath("$[0].professionalId").value(profBId))
                .andDo(print());
    }

    @Test
    @Order(23)
    @DisplayName("GET /professional/me/affiliations — 200 profB vê o convite")
    void listProfessionalAffiliations_invitation() throws Exception {
        Assumptions.assumeTrue(invitationAffiliationId != null);

        mvc.perform(get("/professional/me/affiliations")
                        .header("Authorization", profBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(invitationAffiliationId))
                .andExpect(jsonPath("$[0].type").value("INVITATION"))
                .andDo(print());
    }

    @Test
    @Order(24)
    @DisplayName("PATCH /affiliations/{id}/accept — 200 profB aceita convite → afiliado ao estab2")
    void acceptInvitation_success() throws Exception {
        Assumptions.assumeTrue(invitationAffiliationId != null);

        mvc.perform(patch("/affiliations/{id}/accept", invitationAffiliationId)
                        .header("Authorization", profBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invitationAffiliationId))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.resolvedAt").exists())
                .andDo(print());
    }

    @Test
    @Order(25)
    @DisplayName("POST /establishment/{id}/affiliations — 409 profB já afiliado após accept")
    void inviteProfessional_alreadyAffiliated_409() throws Exception {
        Assumptions.assumeTrue(invitationAffiliationId != null);

        mvc.perform(post("/establishment/{id}/affiliations", estab1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner1Token)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.fromString(profBId)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_ALREADY_AFFILIATED"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // REJECT FLOW — profC solicita, owner2 rejeita
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("POST /professional/me/affiliations — 201 profC solicita afiliação ao estab2")
    void requestForReject_create() throws Exception {
        MvcResult result = mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profCToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab2Id)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        this.rejectAffiliationId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(31)
    @DisplayName("PATCH /affiliations/{id}/reject — 200 owner2 rejeita pedido do profC")
    void rejectRequest_success() throws Exception {
        Assumptions.assumeTrue(rejectAffiliationId != null);

        mvc.perform(patch("/affiliations/{id}/reject", rejectAffiliationId)
                        .header("Authorization", owner2Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(rejectAffiliationId))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andDo(print());
    }

    @Test
    @Order(32)
    @DisplayName("PATCH /affiliations/{id}/accept — 403 tentar aceitar pedido já rejeitado")
    void acceptRejected_403() throws Exception {
        Assumptions.assumeTrue(rejectAffiliationId != null);

        mvc.perform(patch("/affiliations/{id}/accept", rejectAffiliationId)
                        .header("Authorization", owner2Token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ACCESS_DENIED"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // CANCEL FLOW — profD solicita, profD cancela
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @DisplayName("POST /professional/me/affiliations — 201 profD solicita afiliação ao estab1")
    void requestForCancel_create() throws Exception {
        MvcResult result = mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profDToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab1Id)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        this.cancelAffiliationId = new JSONObject(result.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(41)
    @DisplayName("DELETE /affiliations/{id} — 200 profD cancela seu próprio pedido")
    void cancelRequest_success() throws Exception {
        Assumptions.assumeTrue(cancelAffiliationId != null);

        mvc.perform(delete("/affiliations/{id}", cancelAffiliationId)
                        .header("Authorization", profDToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(cancelAffiliationId))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andDo(print());
    }

    @Test
    @Order(42)
    @DisplayName("DELETE /affiliations/{id} — 403 tentar cancelar pedido já cancelado")
    void cancelAlreadyCancelled_403() throws Exception {
        Assumptions.assumeTrue(cancelAffiliationId != null);

        mvc.perform(delete("/affiliations/{id}", cancelAffiliationId)
                        .header("Authorization", profDToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AFFILIATION_ACCESS_DENIED"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ERROR CASES — REQUEST endpoints
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @DisplayName("POST /professional/me/affiliations — 401 sem token")
    void requestAffiliation_noToken_401() throws Exception {
        mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab1Id)))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(51)
    @DisplayName("POST /professional/me/affiliations — 403 CLIENT sem role PROFESSIONAL")
    void requestAffiliation_clientOnly_403() throws Exception {
        mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.fromString(estab1Id)))))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(52)
    @DisplayName("POST /professional/me/affiliations — 404 establishment inexistente")
    void requestAffiliation_estabNotFound_404() throws Exception {
        mvc.perform(post("/professional/me/affiliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profCToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationRequestDTO(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(53)
    @DisplayName("GET /professional/me/affiliations — 401 sem token")
    void listProfessionalAffiliations_noToken_401() throws Exception {
        mvc.perform(get("/professional/me/affiliations"))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ERROR CASES — INVITATION endpoints
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    @DisplayName("POST /establishment/{id}/affiliations — 401 sem token")
    void inviteProfessional_noToken_401() throws Exception {
        mvc.perform(post("/establishment/{id}/affiliations", estab1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.fromString(profCId)))))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(61)
    @DisplayName("POST /establishment/{id}/affiliations — 403 CLIENT sem role OWNER")
    void inviteProfessional_clientOnly_403() throws Exception {
        mvc.perform(post("/establishment/{id}/affiliations", estab1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.fromString(profCId)))))
                .andExpect(status().isForbidden())
                .andDo(print());
    }

    @Test
    @Order(62)
    @DisplayName("POST /establishment/{id}/affiliations — 403 owner errado (owner2 tenta convidar no estab1)")
    void inviteProfessional_wrongOwner_403() throws Exception {
        mvc.perform(post("/establishment/{id}/affiliations", estab1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner2Token)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.fromString(profCId)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_ACCESS_DENIED"))
                .andDo(print());
    }

    @Test
    @Order(63)
    @DisplayName("POST /establishment/{id}/affiliations — 404 profissional inexistente")
    void inviteProfessional_profNotFound_404() throws Exception {
        mvc.perform(post("/establishment/{id}/affiliations", estab1Id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner1Token)
                        .content(mapper.writeValueAsString(
                                new CreateAffiliationInvitationDTO(UUID.randomUUID()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"))
                .andDo(print());
    }

    // ─────────────────────────────────────────────────────────────
    // ERROR CASES — RESOLVE endpoints (accept/reject/cancel)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(70)
    @DisplayName("PATCH /affiliations/{random}/accept — 404 affiliation inexistente")
    void acceptAffiliation_notFound_404() throws Exception {
        mvc.perform(patch("/affiliations/{id}/accept", UUID.randomUUID())
                        .header("Authorization", owner1Token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AFFILIATION_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(71)
    @DisplayName("PATCH /affiliations/{random}/reject — 404 affiliation inexistente")
    void rejectAffiliation_notFound_404() throws Exception {
        mvc.perform(patch("/affiliations/{id}/reject", UUID.randomUUID())
                        .header("Authorization", owner1Token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AFFILIATION_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(72)
    @DisplayName("DELETE /affiliations/{random} — 404 affiliation inexistente")
    void cancelAffiliation_notFound_404() throws Exception {
        mvc.perform(delete("/affiliations/{id}", UUID.randomUUID())
                        .header("Authorization", owner1Token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AFFILIATION_NOT_FOUND"))
                .andDo(print());
    }

    @Test
    @Order(73)
    @DisplayName("PATCH /affiliations/{id}/accept — 401 sem token")
    void acceptAffiliation_noToken_401() throws Exception {
        mvc.perform(patch("/affiliations/{id}/accept", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @Test
    @Order(74)
    @DisplayName("DELETE /affiliations/{id} — 401 sem token")
    void cancelAffiliation_noToken_401() throws Exception {
        mvc.perform(delete("/affiliations/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        log.info("[Affiliation-IT] tearDown");
        affiliationRepository.deleteAll();
        professionalRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
