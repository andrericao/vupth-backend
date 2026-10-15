package com.ospaulos.vupth.integrations.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.campaign.model.*;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /campaigns (DEC-64): a publica aparece sem login; o cupom privado respeita perfil, uma vez e o limite. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampaignIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CampaignRepository campaignRepository;

    private final Faker faker = new Faker();

    private Campaign campaign(CampaignVisibility visibility, CampaignAudience audience, Integer max) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setCode("T" + faker.number().digits(8));
        c.setName("Teste " + c.getCode());
        c.setType(CampaignType.DISCOUNT);
        c.setVisibility(visibility);
        c.setAudience(audience);
        c.setValueAmount(new BigDecimal("5.00"));
        c.setStartsAt(OffsetDateTime.now().minusDays(1));
        c.setEndsAt(OffsetDateTime.now().plusDays(27));
        c.setMaxRedemptions(max);
        return campaignRepository.save(c);
    }

    private org.springframework.test.web.servlet.ResultActions redeem(String token, String code) throws Exception {
        return mvc.perform(post("/campaigns/redeem").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    @Test
    @DisplayName("publica no ar aparece em /campaigns/active sem login; a privada nao")
    void activeIsPublic() throws Exception {
        Campaign pub = campaign(CampaignVisibility.PUBLIC, null, null);
        Campaign priv = campaign(CampaignVisibility.PRIVATE, null, null);

        mvc.perform(get("/campaigns/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]", pub.getId()).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]", priv.getId()).doesNotExist());
    }

    @Test
    @DisplayName("cupom: aceita (201), de novo 409, perfil errado 403, sem vaga 409, inexistente 404, sem login 401")
    void redeemRules() throws Exception {
        var ana = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var beto = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        Campaign forClients = campaign(CampaignVisibility.PRIVATE, CampaignAudience.CLIENT, 1);
        Campaign forOwners = campaign(CampaignVisibility.PRIVATE, CampaignAudience.OWNER, null);

        redeem(ana.token(), forClients.getCode().toLowerCase())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(forClients.getCode()))
                .andExpect(jsonPath("$.redeemed_at").exists());
        redeem(ana.token(), forClients.getCode())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_ALREADY_REDEEMED"));
        redeem(beto.token(), forClients.getCode())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_AVAILABLE"));
        redeem(beto.token(), forOwners.getCode())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOR_YOU"));
        redeem(beto.token(), "NAOEXISTE")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMPAIGN_NOT_FOUND"));
        mvc.perform(post("/campaigns/redeem").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"X\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/campaigns/me").header("Authorization", ana.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(forClients.getId().toString()));
    }
}
