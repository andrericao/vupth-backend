package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentQuickCreateRequest;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** multi-loja-do-dono (17/10): o dono abre ate 5 lojas; a sexta e 409 OWNER_STORE_LIMIT. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OwnerStoreLimitIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("cinco lojas passam; a sexta da 409 OWNER_STORE_LIMIT; outro dono nao e afetado")
    void limit() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        for (int i = 0; i < 5; i++) {
            TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        }
        mvc.perform(post("/establishment/quick-create").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token())
                        .content(mapper.writeValueAsString(
                                new EstablishmentQuickCreateRequest("Loja 6", ServiceType.BARBER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWNER_STORE_LIMIT"));

        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeOwner(mvc, mapper, faker, other.token());
    }
}
