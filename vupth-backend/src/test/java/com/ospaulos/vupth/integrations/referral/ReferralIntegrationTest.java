package com.ospaulos.vupth.integrations.referral;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /referrals (BAK-9): o profissional ve o proprio link; o cliente que chegou por ele fica ligado. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferralIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    private TestUserUtils.UserAuthAndToken professional() throws Exception {
        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.PROFESSIONAL);
        userRepository.save(base);
        return user;
    }

    private static String claim(String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    @Test
    @DisplayName("link do profissional, o cliente entra por ele, conta 1; repetido ok, outro 409, o proprio 422")
    void flow() throws Exception {
        var pro = professional();
        String code = mapper.readTree(mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.referred_clients").value(0))
                        .andReturn().getResponse().getContentAsString())
                .get("code").asText();
        mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.link").value("https://vupth.pages.dev/i/" + code));

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/referrals/claim").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(claim(code.toLowerCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.referred_at").exists());
        mvc.perform(post("/referrals/claim").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(claim(code)))
                .andExpect(status().isOk());
        mvc.perform(get("/referrals/me").header("Authorization", pro.token()))
                .andExpect(jsonPath("$.referred_clients").value(1));

        var other = professional();
        String otherCode = mapper.readTree(mvc.perform(get("/referrals/me").header("Authorization", other.token()))
                .andReturn().getResponse().getContentAsString()).get("code").asText();
        mvc.perform(post("/referrals/claim").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(claim(otherCode)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERRAL_ALREADY_SET"));
        mvc.perform(post("/referrals/claim").header("Authorization", pro.token())
                        .contentType(MediaType.APPLICATION_JSON).content(claim(code)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("REFERRAL_SELF"));
        mvc.perform(post("/referrals/claim").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(claim("ZZZZZZ")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("REFERRAL_NOT_FOUND"));
    }

    @Test
    @DisplayName("todo papel tem link (cliente tambem, DEC-113); sem login, 401; codigo vazio, 422")
    void guards() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/referrals/me").header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.campaigns.length()").value(0))
                .andExpect(jsonPath("$.pending_release").value(0));
        mvc.perform(get("/referrals/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/referrals/claim").header("Authorization", client.token())
                        .contentType(MediaType.APPLICATION_JSON).content(claim("")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
