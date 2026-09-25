package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — <b>quem nao marcou "trabalhando" nao recebe fila</b> (regra de 07/09, fechada em
 * 20/09).
 *
 * <p>O gate do {@code accepting_new} sozinho nao cobria o caso: ir para Fora fecha a fila (19/09),
 * mas o profissional <b>nasce {@code OFF} com {@code accepting_new = true}</b>. O recem-cadastrado,
 * que nunca tocou no status, recebia cliente numa loja que um COLEGA abriu — e o cliente ficava
 * esperando quem nem chegou na loja.</p>
 *
 * <p>A loja aqui esta aberta o tempo todo por causa do colega em expediente, entao o que se testa e
 * so o profissional: o portao da loja (409 {@code ESTABLISHMENT_NOT_OPEN}) nunca entra em cena.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OutOfShiftQueueGateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    /** O colega que abre a loja e fica trabalhando o tempo todo. */
    private String colegaToken, colegaId;
    /** O recem-cadastrado: tem servico, nunca marcou status — nasce OFF com a fila aberta. */
    private String novatoToken, novatoId;
    private String establishmentId;
    private UUID servicoDoNovato, servicoDoColega;
    private String clientToken, clientId;

    @BeforeAll
    void setup() throws Exception {
        var colega = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.colegaToken = colega.token();
        this.colegaId = colega.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, colegaToken).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, colegaToken, establishmentId, "Colega");
        this.servicoDoColega = UUID.fromString(TestUserUtils.createServiceItem(mvc, mapper, establishmentId,
                colegaToken, ServiceItemType.CORTE_MAQUINA, "Corte do colega", BigDecimal.valueOf(30)).id());

        var novato = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.novatoToken = novato.token();
        this.novatoId = novato.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, novatoToken, establishmentId, "Novato");
        this.servicoDoNovato = UUID.fromString(TestUserUtils.createServiceItem(mvc, mapper, establishmentId,
                novatoToken, ServiceItemType.BARBA, "Barba do novato", BigDecimal.valueOf(20)).id());

        for (String id : new String[]{colegaId, novatoId}) {
            professionalIntervalRepository.deleteAll(
                    professionalIntervalRepository.findByProfessional_Id(UUID.fromString(id)));
        }

        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = client.token();
        this.clientId = client.id();

        // O colega chega: a loja abre por presenca e fica aberta durante toda a classe.
        setStatus(colegaToken, "WORKING");
    }

    private void setStatus(String token, String status) throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions entrarNaFila(String professionalId, UUID serviceItem, OffsetDateTime targetTime)
            throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(clientId),
                List.of(serviceItem),
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(),
                30,
                OffsetDateTime.now(),
                targetTime);
        return mvc.perform(post("/attendances")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", clientToken)
                .content(mapper.writeValueAsString(request)));
    }

    @Test
    @Order(10)
    @DisplayName("o recem-cadastrado que nunca marcou status NAO recebe cliente — 409")
    void neverSetStatus_doesNotReceiveQueue() throws Exception {
        entrarNaFila(novatoId, servicoDoNovato, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_ACCEPTING_NEW"))
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("nem por intencao de fila (targetTime futuro) — a regra e a mesma da fila fechada")
    void neverSetStatus_intentAlsoBlocked() throws Exception {
        entrarNaFila(novatoId, servicoDoNovato, OffsetDateTime.now().plusHours(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_ACCEPTING_NEW"));
    }

    @Test
    @Order(30)
    @DisplayName("o colega EM expediente recebe normalmente — a loja nao e o problema")
    void colleagueInShift_receives() throws Exception {
        entrarNaFila(colegaId, servicoDoColega, null)
                .andExpect(status().isCreated())
                .andDo(print());
    }

    @Test
    @Order(40)
    @DisplayName("marcando trabalhando, o mesmo profissional passa a receber")
    void afterStartingShift_receives() throws Exception {
        setStatus(novatoToken, "WORKING");

        entrarNaFila(novatoId, servicoDoNovato, null)
                .andExpect(status().isCreated())
                .andDo(print());
    }

    @Test
    @Order(50)
    @DisplayName("em INTERVALO continua recebendo — e almoco, a pessoa volta")
    void onBreak_stillReceives() throws Exception {
        // O cliente do Order 40 ainda esta na fila do novato, entao quem entra agora e outro.
        var outroCliente = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientToken = outroCliente.token();
        this.clientId = outroCliente.id();

        setStatus(novatoToken, "ON_BREAK");

        entrarNaFila(novatoId, servicoDoNovato, null)
                .andExpect(status().isCreated())
                .andDo(print());
    }
}
