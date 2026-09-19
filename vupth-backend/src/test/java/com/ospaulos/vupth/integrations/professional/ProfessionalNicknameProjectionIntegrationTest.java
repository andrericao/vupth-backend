package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — apelido (nickname) exposto nas projecoes de profissional
 * para o CLIENTE e o OWNER (item A do pedido do front).
 *
 * O apelido vive na BaseUser e e escrito via PATCH /profile/general-infos; aqui
 * validamos que ele aparece em:
 *  - GET /establishment/id/{id}/professionals-with-queue (tela do estabelecimento — cliente)
 *  - GET /professional (busca do owner para convidar)
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalNicknameProjectionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired UserRepository userRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, establishmentId;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();

        // solo shop: o mesmo usuario vira profissional (baseUser compartilhado)
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Roberto");

        // service item para o profissional aparecer na fila (gate #31)
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));

        // 18/09: so quem esta em expediente aparece no professionals-with-queue — quem nao marcou
        // "trabalhando" entende-se que esta em casa.
        TestUserUtils.putEveryoneToWork(professionalRepository, establishmentId);

        // define o apelido pelo ponto unico de escrita
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"nickname\":\"Bibba\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(10)
    @DisplayName("GET professionals-with-queue — 200 tela do estabelecimento expoe o apelido")
    void withQueue_exposesNickname() throws Exception {
        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals").isArray())
                .andExpect(jsonPath("$.professionals[0].nickname").value("Bibba"))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET /professional — 200 busca do owner expoe o apelido (candidato a convite, sem establishment)")
    void ownerSearch_exposesNickname() throws Exception {
        // A busca do owner so lista candidatos a convite — profissionais SEM establishment
        // (ver #18). Registramos um profissional avulso, definimos seu apelido e confirmamos
        // que a projecao da busca o expoe. Localizamos pelo apelido (busca e global).
        var freeProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/professional/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", freeProf.token())
                        .content("{\"name\":\"Solto\",\"specialty\":\"Corte\"}"))
                .andExpect(status().isCreated());
        mvc.perform(patch("/profile/general-infos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", freeProf.token())
                        .content("{\"nickname\":\"BibbaLivre\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/professional")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.nickname == 'BibbaLivre')]").exists())
                .andDo(print());
    }

    @Test
    @Order(12)
    @DisplayName("GET /professional/me — 200 o proprio profissional ve seu apelido (BUG10)")
    void me_exposesNickname() throws Exception {
        mvc.perform(get("/professional/me")
                        .header("Authorization", ownerProfToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Bibba"))
                .andDo(print());
    }

    @Test
    @Order(13)
    @DisplayName("PATCH /professional/me — 200 editar a especialidade nao derruba o apelido do read-back")
    void patchMe_keepsNicknameInResponse() throws Exception {
        mvc.perform(patch("/professional/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"specialty\":\"Barba\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.specialty").value("Barba"))
                .andExpect(jsonPath("$.nickname").value("Bibba"))
                .andDo(print());
    }

    @AfterAll
    void tearDown() {
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
