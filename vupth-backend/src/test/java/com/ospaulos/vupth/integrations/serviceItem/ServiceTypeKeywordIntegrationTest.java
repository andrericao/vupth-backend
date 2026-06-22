package com.ospaulos.vupth.integrations.serviceItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.professional.dto.request.ProfessionalRegisterRequest;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — palavras-chave/sinonimos de tipo de servico (item 34).
 *
 *  - GET /service-types?q=luzes  resolve o sinonimo regional para o tipo canonico
 *    (PINTURA) + matched_keyword (publico).
 *  - GET /service-types          lista todos os tipos com sinonimos (para o "i").
 *  - POST /service-types/{type}/keywords  profissional adiciona sinonimo; normaliza,
 *    dedup contra base do enum + adicionados (409), so PROFESSIONAL (403 p/ cliente).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ServiceTypeKeywordIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    String professionalToken, clientToken;

    @BeforeAll
    void setup() throws Exception {
        var prof = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.professionalToken = prof.token();
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", professionalToken)
                        .content(mapper.writeValueAsString(
                                new ProfessionalRegisterRequest("Keyword Tester", "Corte"))))
                .andExpect(status().isCreated());

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
    }

    @Test
    @Order(10)
    @DisplayName("GET /service-types?q=luzes — 200 resolve sinonimo regional para PINTURA")
    void search_resolvesRegionalSynonym() throws Exception {
        mvc.perform(get("/service-types").param("q", "luzes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("PINTURA"))
                .andExpect(jsonPath("$[0].display_name").value("Pintura"))
                .andExpect(jsonPath("$[0].matched_keyword").value("luzes"))
                .andExpect(jsonPath("$[0].keywords").isArray())
                .andExpect(jsonPath("$[0].service_types").isArray())
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET /service-types?q=luzes — sinonimo normalizado (acento/caixa ignorados)")
    void search_normalizesQuery() throws Exception {
        mvc.perform(get("/service-types").param("q", "LÚZES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PINTURA"));
    }

    @Test
    @Order(12)
    @DisplayName("GET /service-types — 200 sem q lista todos os tipos com sinonimos")
    void list_allTypes() throws Exception {
        mvc.perform(get("/service-types"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(25))
                .andExpect(jsonPath("$[0].keywords").isArray());
    }

    @Test
    @Order(13)
    @DisplayName("GET /service-types?q=xpto — 200 array vazio quando nada casa")
    void search_noMatch_emptyArray() throws Exception {
        mvc.perform(get("/service-types").param("q", "xptozinho"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @Order(20)
    @DisplayName("POST /service-types/BARBA/keywords — 201 profissional adiciona sinonimo novo")
    void addKeyword_success() throws Exception {
        mvc.perform(post("/service-types/{type}/keywords", "BARBA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", professionalToken)
                        .content("{\"keyword\":\"aparo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("BARBA"))
                .andExpect(jsonPath("$.matched_keyword").value("aparo"))
                .andExpect(jsonPath("$.keywords[?(@ == 'aparo')]").exists())
                .andDo(print());

        // agora a busca pelo sinonimo recem-criado resolve para BARBA
        mvc.perform(get("/service-types").param("q", "aparo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'BARBA')]").exists());
    }

    @Test
    @Order(21)
    @DisplayName("POST /service-types/BARBA/keywords — 409 sinonimo ja existe na base do enum")
    void addKeyword_duplicateBase_409() throws Exception {
        mvc.perform(post("/service-types/{type}/keywords", "BARBA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", professionalToken)
                        .content("{\"keyword\":\"barba\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERVICE_TYPE_KEYWORD_ALREADY_EXISTS"));
    }

    @Test
    @Order(22)
    @DisplayName("POST /service-types/BARBA/keywords — 409 dedup considera normalizacao (APARO == aparo)")
    void addKeyword_duplicateNormalized_409() throws Exception {
        mvc.perform(post("/service-types/{type}/keywords", "BARBA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", professionalToken)
                        .content("{\"keyword\":\"ÁPARO\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(23)
    @DisplayName("POST /service-types/BARBA/keywords — 403 cliente nao pode adicionar")
    void addKeyword_clientForbidden_403() throws Exception {
        mvc.perform(post("/service-types/{type}/keywords", "BARBA")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"keyword\":\"qualquer\"}"))
                .andExpect(status().isForbidden());
    }
}
