package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — S1 (fatia 2): open_status DERIVADO dos work_status dos profissionais.
 *
 * Invariante: {@code aberto ⇔ ∃ prof afiliado em {WORKING, ON_BREAK}}. O recalculo roda (atomico,
 * mesma transacao) a cada PATCH /professional/me/status e a cada afiliacao/saida de profissional
 * que conta como presenca. Modelo HIBRIDO: entre recalculos o toggle manual
 * (PATCH /establishment/{id}/open-status) continua valendo como override.
 *
 * Tambem cobre o work_status exposto no professionals-with-queue (pedido do Partner, handoff S1).
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenStatusDerivationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    String ownerProfToken;   // owner que tambem trabalha (prof A, com servico)
    String profBToken;       // segundo profissional afiliado (prof B, com servico)
    String establishmentId;

    @BeforeAll
    void setup() throws Exception {
        // Prof A = owner que tambem trabalha. Loja nasce CLOSED, todo mundo nasce OFF.
        var userA = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = userA.token();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Prof A");
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte A", BigDecimal.valueOf(30));

        // Prof B = segundo profissional da MESMA loja, com servico proprio.
        var userB = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profBToken = userB.token();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profBToken, establishmentId, "Prof B");
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profBToken,
                ServiceItemType.BARBA, "Barba B", BigDecimal.valueOf(20));

        log.info("[OpenStatusDerivation] setup — estab={}", establishmentId);
    }

    private void patchStatus(String token, String status) throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private void assertOpenStatus(String expected) throws Exception {
        mvc.perform(get("/establishment/id/" + establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value(expected));
    }

    @Test
    @Order(10)
    @DisplayName("1o WORKING abre a loja; ON_BREAK mantem aberta; ultimo OFF fecha")
    void derivation_followsPresence() throws Exception {
        assertOpenStatus("CLOSED"); // loja nasce fechada, todo mundo OFF

        patchStatus(ownerProfToken, "WORKING");
        assertOpenStatus("OPEN"); // ∃ presenca -> abre

        patchStatus(ownerProfToken, "ON_BREAK");
        assertOpenStatus("OPEN"); // intervalo conta como aberto (almoco nao fecha a loja)

        patchStatus(ownerProfToken, "OFF");
        assertOpenStatus("CLOSED"); // saiu o ultimo -> fecha
    }

    @Test
    @Order(20)
    @DisplayName("2 profs: a loja so fecha quando o ULTIMO presente sai")
    void derivation_lastOneOut() throws Exception {
        patchStatus(ownerProfToken, "WORKING");
        patchStatus(profBToken, "WORKING");
        assertOpenStatus("OPEN");

        patchStatus(ownerProfToken, "OFF");
        assertOpenStatus("OPEN"); // B ainda presente

        patchStatus(profBToken, "OFF");
        assertOpenStatus("CLOSED"); // saiu o ultimo
    }

    @Test
    @Order(30)
    @DisplayName("hibrido: toggle manual vale como override, e o proximo recalculo vence")
    void hybrid_manualOverrideThenRecalcWins() throws Exception {
        patchStatus(ownerProfToken, "WORKING");
        assertOpenStatus("OPEN");

        // Override manual: owner fecha na mao (sem fila ativa -> gate BUG6 nao barra).
        mvc.perform(patch("/establishment/" + establishmentId + "/open-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerProfToken)
                        .content("{\"openStatus\":\"CLOSED\"}"))
                .andExpect(status().isOk());
        assertOpenStatus("CLOSED"); // override respeitado entre recalculos

        // Proximo recalculo (mesmo status, novo PATCH) repara: ∃ presenca -> OPEN.
        patchStatus(ownerProfToken, "WORKING");
        assertOpenStatus("OPEN");

        patchStatus(ownerProfToken, "OFF"); // limpa p/ proximos testes
        assertOpenStatus("CLOSED");
    }

    @Test
    @Order(40)
    @DisplayName("professionals-with-queue expoe o work_status de cada profissional")
    void queueSnapshot_exposesWorkStatus() throws Exception {
        patchStatus(ownerProfToken, "WORKING"); // A presente, B OFF (do Order 20/30)

        mvc.perform(get("/establishment/id/" + establishmentId + "/professionals-with-queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[?(@.name == 'Prof A')].work_status").value("WORKING"))
                .andExpect(jsonPath("$.professionals[?(@.name == 'Prof B')].work_status").value("OFF"));

        patchStatus(ownerProfToken, "OFF"); // limpa
    }

    @Test
    @Order(50)
    @DisplayName("saida do ultimo presente (leave) fecha a loja")
    void leave_lastPresentCloses() throws Exception {
        patchStatus(profBToken, "WORKING"); // so B presente
        assertOpenStatus("OPEN");

        // B sai da loja (sem fila propria -> leave permitido) -> recalculo fecha.
        mvc.perform(delete("/professional/me/establishment")
                        .header("Authorization", profBToken))
                .andExpect(status().isNoContent());
        assertOpenStatus("CLOSED");
    }
}
