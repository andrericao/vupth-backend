package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DEC-182 (PAR-10): {@code DELETE /profile/delete/me} responde 200 com {@code still_professional}. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StillProfessionalOnDeleteIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("profissional de OUTRA loja apaga a conta -> still_professional true")
    void professionalOfAnotherStore() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token()).id();
        var worker = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, worker.token(), establishmentId, "Beto");

        mvc.perform(delete("/profile/delete/me").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", worker.token())
                        .content("{\"current_password\":\"" + worker.password() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.still_professional").value(true));
    }

    @Test
    @DisplayName("cliente comum apaga a conta -> still_professional false")
    void plainClient() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        mvc.perform(delete("/profile/delete/me").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content("{\"current_password\":\"" + client.password() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.still_professional").value(false));
    }
}
