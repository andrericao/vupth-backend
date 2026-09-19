package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — coerencia entre a LOJA e os PROFISSIONAIS (decisoes do Andre, 09/09 e 17–18/09).
 *
 * <ul>
 *   <li>quem abre a loja e a <b>chegada do primeiro profissional</b>, nao o relogio (D11): com alguem
 *       trabalhando ela responde aberta mesmo <b>fora</b> do horario publicado;</li>
 *   <li><b>fechar a loja leva todos a {@code OFF}</b> e fecha as filas — os dois estados nao convivem
 *       (no aparelho apareceu loja FECHADA com profissional Trabalhando e fila aceitando novos);</li>
 *   <li>profissional que nao marcou "trabalhando" <b>nao aparece</b> para o cliente
 *       (<i>"entende-se que esta em casa"</i>) — mas <b>intervalo continua aparecendo</b>: e almoco ou
 *       saida rapida, a pessoa volta;</li>
 *   <li>voltar de <i>Fora</i> <b>nao reabre</b> a fila sozinha (regra de 09/09).</li>
 * </ul>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StoreProfessionalCoherenceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    private String ownerToken, ownerId, establishmentId, employeeToken, employeeId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.ownerId = owner.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Dono Profissional");
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte do dono", BigDecimal.valueOf(30));

        var employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.employeeToken = employee.token();
        this.employeeId = employee.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employeeToken, establishmentId, "Funcionario");
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, employeeToken,
                ServiceItemType.BARBA, "Barba do funcionario", BigDecimal.valueOf(20));

        for (String id : new String[]{ownerId, employeeId}) {
            professionalIntervalRepository.deleteAll(
                    professionalIntervalRepository.findByProfessional_Id(UUID.fromString(id)));
        }
        // Horario publicado curto e JA vencido, para provar que o relogio nao manda no "aberto agora".
        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"opening_time\":\"00:01\",\"closing_time\":\"00:02\"}"))
                .andExpect(status().isOk());
    }

    private void setStatus(String token, ProfessionalStatus status) throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private java.util.List<String> professionalsSeenByClient() throws Exception {
        String body = mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var node = mapper.readTree(body).get("professionals");
        var ids = new java.util.ArrayList<String>();
        node.forEach(p -> ids.add(p.get("id").asText()));
        return ids;
    }

    @Test
    @Order(1)
    @DisplayName("com alguém trabalhando, a loja está aberta mesmo FORA do horário publicado")
    void openBecauseSomeoneIsWorking() throws Exception {
        setStatus(ownerToken, ProfessionalStatus.WORKING);

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andExpect(jsonPath("$.is_open").value(true))
                .andExpect(jsonPath("$.opening_time").value("00:01"))
                .andDo(print());
    }

    @Test
    @Order(2)
    @DisplayName("quem está em INTERVALO continua aparecendo — é almoço ou saída rápida")
    void breakStillShows() throws Exception {
        setStatus(employeeToken, ProfessionalStatus.ON_BREAK);

        assertThat(professionalsSeenByClient()).contains(employeeId, ownerId);
    }

    @Test
    @Order(3)
    @DisplayName("quem está FORA some da loja — entende-se que está em casa")
    void offDisappears() throws Exception {
        setStatus(employeeToken, ProfessionalStatus.OFF);

        assertThat(professionalsSeenByClient()).contains(ownerId).doesNotContain(employeeId);

        mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(jsonPath("$.professionals", hasSize(1)))
                .andDo(print());
    }

    @Test
    @Order(4)
    @DisplayName("fechar a loja leva TODOS a Fora e fecha as filas")
    void closingTheStoreSendsEveryoneHome() throws Exception {
        setStatus(employeeToken, ProfessionalStatus.WORKING);

        mvc.perform(patch("/establishment/{id}/open-status", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("CLOSED"))
                .andExpect(jsonPath("$.is_open").value(false))
                .andDo(print());

        for (String id : new String[]{ownerId, employeeId}) {
            var professional = professionalRepository.findById(UUID.fromString(id)).orElseThrow();
            assertThat(professional.getWorkStatus()).as("profissional %s foi para Fora", id)
                    .isEqualTo(ProfessionalStatus.OFF);
            assertThat(professional.getAcceptingNew()).as("fila de %s fechada", id).isFalse();
        }
        assertThat(professionalsSeenByClient()).as("loja fechada não mostra ninguém").isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("voltar de Fora NÃO reabre a fila sozinha — quem fechou decide quando abrir")
    void comingBackDoesNotReopenTheQueue() throws Exception {
        setStatus(ownerToken, ProfessionalStatus.WORKING);

        var professional = professionalRepository.findById(UUID.fromString(ownerId)).orElseThrow();
        assertThat(professional.getWorkStatus()).isEqualTo(ProfessionalStatus.WORKING);
        assertThat(professional.getAcceptingNew()).as("a fila continua fechada até ele abrir").isFalse();

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andDo(print());
    }
}
