package com.ospaulos.vupth.integrations.establishment;

import com.ospaulos.vupth.VupthApplication;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regressao do 500 (UnexpectedRollbackException) em GET /establishment.
 *
 * Causa em producao: o banco tinha service_item com service_item_type legado
 * (NAVALHA/PROCEDIMENTO) que nao existe mais no enum ServiceItemType. Ao materializar
 * o ServiceItem dentro do snapshot de fila (GetEstablishmentWithQueueUseCase, chamado
 * em lote por EstablishmentServiceImpl.minWaitMinutes), o Hibernate lancava
 * "No enum constant ...". Esse use case e @Transactional e juntava na transacao da
 * listagem; a excecao — mesmo sendo "engolida" pelo catch de minWaitMinutes — marcava
 * a transacao compartilhada como rollback-only, e o commit final estourava
 * UnexpectedRollbackException, derrubando TODA a listagem com 500.
 *
 * Correcao coberta aqui: GetEstablishmentWithQueueUseCase.execute roda em
 * Propagation.REQUIRES_NEW, isolando a falha de UM establishment — a listagem degrada
 * o tempo de espera para null em vez de cair inteira.
 *
 * (A correcao definitiva do dado legado e a migration 022-remap-legacy-serviceitem-types.)
 *
 * Reaproveita o service_item semeado de Carlos (Barbearia do Ricardo, ACTIVE) e o
 * sabota com um valor de enum invalido, restaurando ao fim.
 */
@Slf4j
@SpringBootTest(classes = VupthApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Este teste depende do dado SEMEADO (012-seed-data) estar presente e listavel na
// GET /establishment. Sob reuseForks=true o DB e compartilhado entre classes e varias
// chamam deleteAll(), apagando o seed; um contexto reusado nao o restaura. BEFORE_CLASS
// forca um contexto fresco -> Liquibase drop-first re-roda -> seed re-inserido antes desta
// classe. Custo: um boot extra para esta unica classe (desprezivel perto do ganho do reuse).
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@DisplayName("GET /establishment — resiliente a service_item_type legado/invalido")
class LegacyServiceItemTypeResilienceIntegrationTest {

    // service_item semeado de Carlos (012-seed-data.yaml), professional em establishment ACTIVE.
    private static final String SEEDED_SERVICE_ITEM_ID = "f209672a-3e01-4941-a8ff-550dfdf277a9";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void restoreSeed() {
        // Restaura o valor valido para nao contaminar outros testes.
        jdbc.update("UPDATE service_item SET service_item_type = 'CORTE_MAQUINA' WHERE id = ?::uuid",
                SEEDED_SERVICE_ITEM_ID);
    }

    @Test
    @DisplayName("um service_item com enum legado nao derruba a listagem inteira (200, wait degradado)")
    void listAllActive_withLegacyEnumValue_doesNotFailWhole() throws Exception {
        // Simula o dado legado de producao.
        jdbc.update("UPDATE service_item SET service_item_type = 'NAVALHA' WHERE id = ?::uuid",
                SEEDED_SERVICE_ITEM_ID);

        // Sem o REQUIRES_NEW, este GET retornava 500 (UnexpectedRollbackException).
        mvc.perform(get("/establishment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").exists());
    }
}
