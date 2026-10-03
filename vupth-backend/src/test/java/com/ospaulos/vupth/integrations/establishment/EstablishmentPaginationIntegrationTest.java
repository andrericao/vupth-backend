package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.establishment.dto.request.EstablishmentQuickCreateRequest;
import com.ospaulos.vupth.domain.establishment.valueObject.ServiceType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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

/**
 * Integration tests — vitrine paginada (BAK-2, 03/10). A paginacao e opt-in: sem {@code page} nem
 * {@code size} a resposta segue sendo a lista inteira, porque o app em campo le um array.
 * As lojas do teste tem um prefixo unico no nome e toda busca filtra por ele.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentPaginationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();
    private final String prefix = "Pag" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    void setup() throws Exception {
        for (int i = 1; i <= 3; i++) {
            var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            mvc.perform(post("/establishment/quick-create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", owner.token())
                            .content(mapper.writeValueAsString(
                                    new EstablishmentQuickCreateRequest(prefix + " loja " + i, ServiceType.BARBER))))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    @DisplayName("sem page/size: a lista inteira, como array — compativel com o app em campo")
    void semPaginacao_listaInteira() throws Exception {
        mvc.perform(get("/establishment").param("name", prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("page=0&size=2: envelope com 2 itens, 3 no total, 2 paginas e proxima")
    void primeiraPagina() throws Exception {
        mvc.perform(get("/establishment").param("name", prefix).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("page=1&size=2: a ultima loja, sem proxima; pagina alem do fim vem vazia")
    void ultimaPaginaEAlem() throws Exception {
        mvc.perform(get("/establishment").param("name", prefix).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));

        mvc.perform(get("/establishment").param("name", prefix).param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("page negativa ou size fora de 1..100: 422")
    void valoresImpossiveis_422() throws Exception {
        mvc.perform(get("/establishment").param("page", "-1"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/establishment").param("size", "0"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/establishment").param("size", "101"))
                .andExpect(status().isUnprocessableEntity());
    }
}
