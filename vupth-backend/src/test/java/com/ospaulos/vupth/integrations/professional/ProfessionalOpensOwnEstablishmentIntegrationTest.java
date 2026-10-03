package com.ospaulos.vupth.integrations.professional;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — o profissional abre o proprio estabelecimento (abrir-propria-loja, Partner S23).
 *
 * <p>O caminho ja existe com as rotas de hoje, sem endpoint novo: {@code POST /establishment/quick-create}
 * acrescenta a role OWNER (roles acumulativas) e cria a loja; para atender nela, o profissional sai da loja
 * atual ({@code DELETE /professional/me/establishment}) e se afilia a nova ({@code POST /professional/affiliate}).
 * As roles vem do banco a cada request, entao o MESMO token ja serve para as rotas de dono — nao precisa
 * de novo login.</p>
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalOpensOwnEstablishmentIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("profissional vinculado a outra loja abre a propria, vira dono e passa a atender nela, com o mesmo token")
    void professionalOpensOwnEstablishment() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var otherShop = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, pro.token(), otherShop.id());

        // 1. Abre a propria loja: ganha a role OWNER sem perder a PROFESSIONAL.
        var ownShop = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token());

        // 2. Rota de dono com o mesmo token.
        mvc.perform(get("/establishment/" + ownShop.id() + "/reports")
                        .header("Authorization", pro.token()))
                .andExpect(status().isOk());

        // 3. Sai da loja antiga e passa a atender na nova.
        mvc.perform(delete("/professional/me/establishment")
                        .header("Authorization", pro.token()))
                .andExpect(status().isNoContent());

        mvc.perform(post("/professional/affiliate")
                        .header("Authorization", pro.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establishmentId\":\"" + ownShop.id() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.establishment_id").value(ownShop.id()));
    }
}
