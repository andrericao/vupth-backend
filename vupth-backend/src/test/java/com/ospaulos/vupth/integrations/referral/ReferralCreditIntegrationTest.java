package com.ospaulos.vupth.integrations.referral;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bonificacao por link, o credito (BAK-9, fatia 2): o cliente entra pelo link do dono que atende, corta, e o
 * Finalizar gera o credito com o valor da campanha; o segundo corte nao gera outro.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferralCreditIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    private String adminToken() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        return user.token();
    }

    private String campaign() {
        return String.format("{\"name\":\"Indique e ganhe\",\"type\":\"REFERRAL_BONUS\",\"visibility\":\"PUBLIC\","
                        + "\"value_amount\":5.00,\"starts_at\":\"%s\",\"ends_at\":\"%s\",\"new_clients_only\":true,"
                        + "\"referral_professional_percent\":60,\"referral_owner_percent\":40,"
                        + "\"referral_scope\":\"REFERRER_ESTABLISHMENT\",\"payout_period\":\"MONTHLY\",\"payout_day\":5}",
                OffsetDateTime.now().minusHours(1), OffsetDateTime.now().plusDays(28));
    }

    private void cut(String profId, String establishmentId, String clientId, String clientToken, String profToken,
                     List<UUID> items) throws Exception {
        AttendanceRequest request = new AttendanceRequest(UUID.fromString(profId), UUID.fromString(establishmentId),
                UUID.fromString(clientId), items, AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(5), 30,
                OffsetDateTime.now());
        String id = new JSONObject(mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).getString("id");
        mvc.perform(patch("/attendances/{id}/start", id).header("Authorization", profToken)).andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", id).header("Authorization", profToken)).andExpect(status().isOk());
        mvc.perform(post("/attendances/{id}/payment/confirm", id).header("Authorization", profToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("cliente pelo link do dono que atende: o Finalizar credita a ele a parte do dono (R$ 2, 40% de R$ 5), a do profissional vai a quem cortou; o segundo corte nao")
    void creditOnFirstCut() throws Exception {
        String admin = adminToken();
        String campaignId = new JSONObject(mvc.perform(post("/admin/campaigns").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(campaign()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.referral_scope").value("REFERRER_ESTABLISHMENT"))
                .andReturn().getResponse().getContentAsString()).getString("id");

        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token()).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        // antifraude (DEC-117): o profissional do corte e outro, nao o dono que compartilhou o link
        var barber = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, barber.token(), establishmentId, "Beto");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        var item = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, barber.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        List<UUID> items = List.of(UUID.fromString(item.id()));

        // compartilhe-e-ganhe (DEC-113): so rende premio a campanha em que quem indica entrou (Participar)
        mvc.perform(post("/campaigns/{id}/join", campaignId).header("Authorization", pro.token()))
                .andExpect(status().isCreated());
        String code = new JSONObject(mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).getString("code");
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/referrals/claim").header("Authorization", client.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")).andExpect(status().isOk());

        cut(barber.id(), establishmentId, client.id(), client.token(), barber.token(), items);
        mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.referred_clients").value(1))
                .andExpect(jsonPath("$.credited_clients").value(1))
                .andExpect(jsonPath("$.pending_release").value(2.0))
                .andExpect(jsonPath("$.to_receive").value(0));

        cut(barber.id(), establishmentId, client.id(), client.token(), barber.token(), items);
        mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.credited_clients").value(1))
                .andExpect(jsonPath("$.pending_release").value(2.0));

        // O premio nasce travado: o Andre libera (so entao entra no "a receber" e no painel de pagamentos)
        mvc.perform(post("/admin/referrals/release").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"campaign_id\":\"" + campaignId + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.pending_release").value(0))
                .andExpect(jsonPath("$.to_receive").value(2.0));

        // O painel ve o que pagar, paga a mao e marca; o "a receber" zera
        String row = "$[?(@.beneficiary_id == '" + pro.id() + "')]";
        String payouts = mvc.perform(get("/admin/referrals/payouts").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + ".amount").value(2.0))
                .andExpect(jsonPath(row + ".payout_period").value("MONTHLY"))
                .andReturn().getResponse().getContentAsString();
        String payoutCampaignId = com.jayway.jsonpath.JsonPath.<List<String>>read(payouts, row + ".campaign_id").get(0);
        mvc.perform(post("/admin/referrals/payouts/{c}/{u}/paid", payoutCampaignId, pro.id()).header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid_credits").value(1));
        mvc.perform(post("/admin/referrals/payouts/{c}/{u}/paid", payoutCampaignId, pro.id()).header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFERRAL_NOTHING_TO_PAY"));
        mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.to_receive").value(0));
        mvc.perform(get("/admin/referrals/payouts").header("Authorization", pro.token()))
                .andExpect(status().isForbidden());
    }
}
