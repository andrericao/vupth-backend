package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.CampaignVisibility;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** admin-funil-da-campanha: GET /admin/campaigns/{id}/funnel, so agregado e o nome de quem resgatou. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminCampaignFunnelIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CampaignRepository campaignRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    private Campaign campaign(CampaignType type) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setCode("F" + faker.number().digits(8));
        c.setName("Funil " + c.getCode());
        c.setType(type);
        c.setVisibility(CampaignVisibility.PUBLIC);
        c.setValueAmount(new BigDecimal("5.00"));
        c.setStartsAt(OffsetDateTime.now().minusDays(1));
        c.setEndsAt(OffsetDateTime.now().plusDays(27));
        return campaignRepository.save(c);
    }

    @Test
    @DisplayName("funil do cupom: conta quem resgatou e lista so o nome; link aberto sai como nao medido; 404 sem campanha; 403 sem ser admin")
    void funnel() throws Exception {
        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var buyer = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(buyer.id())).orElseThrow();
        base.setFirstName("Beto" + UUID.randomUUID().toString().substring(0, 6));
        base.setLastName("Silva");
        userRepository.save(base);
        Campaign cupom = campaign(CampaignType.DISCOUNT);

        mvc.perform(post("/campaigns/redeem").header("Authorization", buyer.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + cupom.getCode() + "\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/admin/campaigns/{id}/funnel", cupom.getId()).header("Authorization", admin.token()))
                .andExpect(status().isForbidden());
        BaseUser adminBase = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        adminBase.addRole(RoleType.ADMIN);
        userRepository.save(adminBase);

        mvc.perform(get("/admin/campaigns/{id}/funnel", cupom.getId()).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.campaign_id").value(cupom.getId().toString()))
                .andExpect(jsonPath("$.type").value("DISCOUNT"))
                .andExpect(jsonPath("$.redeemed").value(1))
                .andExpect(jsonPath("$.accounts_created").value(0))
                .andExpect(jsonPath("$.first_cut_done").value(0))
                .andExpect(jsonPath("$.link_opened").doesNotExist())
                .andExpect(jsonPath("$.link_opened_measured").value(false))
                .andExpect(jsonPath("$.redeemers", hasSize(1)))
                .andExpect(jsonPath("$.redeemers[0].name").value(base.getFirstName() + " Silva"))
                .andExpect(jsonPath("$.redeemers[0].email").doesNotExist());

        // janela no passado distante: nada
        mvc.perform(get("/admin/campaigns/{id}/funnel", cupom.getId())
                        .param("from", "2020-01-01T00:00:00Z").param("to", "2020-02-01T00:00:00Z")
                        .header("Authorization", admin.token()))
                .andExpect(jsonPath("$.redeemed").value(0));

        Campaign link = campaign(CampaignType.REFERRAL_BONUS);
        mvc.perform(get("/admin/campaigns/{id}/funnel", link.getId()).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("REFERRAL_BONUS"))
                .andExpect(jsonPath("$.redeemers", hasSize(0)));

        mvc.perform(get("/admin/campaigns/{id}/funnel", UUID.randomUUID()).header("Authorization", admin.token()))
                .andExpect(status().isNotFound());
    }
}
