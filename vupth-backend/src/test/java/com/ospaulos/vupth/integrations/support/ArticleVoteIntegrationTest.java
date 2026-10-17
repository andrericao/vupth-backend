package com.ospaulos.vupth.integrations.support;

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

/** voto-do-artigo: voto publico (204), troca do voto no dia, 422 de slug e voto, leitura so para ADMIN. */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ArticleVoteIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    private static String vote(String v) {
        return "{\"vote\":\"" + v + "\"}";
    }

    @Test
    @DisplayName("sem login: 204; o mesmo IP trocando o voto nao soma; o painel le a contagem; usuario comum: 403")
    void voteAndRead() throws Exception {
        String slug = "artigo-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/support/articles/{slug}/vote", slug).contentType(MediaType.APPLICATION_JSON)
                        .content(vote("UP")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/support/articles/{slug}/vote", slug).contentType(MediaType.APPLICATION_JSON)
                        .content(vote("DOWN")))
                .andExpect(status().isNoContent());

        var user = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/admin/support/article-votes").header("Authorization", user.token()))
                .andExpect(status().isForbidden());

        BaseUser base = userRepository.findById(UUID.fromString(user.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        mvc.perform(get("/admin/support/article-votes").header("Authorization", user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug=='" + slug + "')].up").value(0))
                .andExpect(jsonPath("$[?(@.slug=='" + slug + "')].down").value(1))
                .andExpect(jsonPath("$[?(@.slug=='" + slug + "')].last_vote_at").isNotEmpty());
    }

    @Test
    @DisplayName("slug fora do formato: 422 INVALID_ARTICLE_SLUG; voto invalido ou ausente: 422 VALIDATION_ERROR")
    void invalid() throws Exception {
        mvc.perform(post("/support/articles/{slug}/vote", "Slug_Ruim").contentType(MediaType.APPLICATION_JSON)
                        .content(vote("UP")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_ARTICLE_SLUG"));
        mvc.perform(post("/support/articles/{slug}/vote", "ok").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
