package com.ospaulos.vupth.integrations.establishment;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — "aberto agora" efetivo no contrato HTTP.
 *
 * <p><b>Regra de 17–18/09 (D11 do Client):</b> quem abre a loja e a chegada do primeiro
 * profissional, nao o relogio. O {@code EstablishmentResponse} expoe {@code is_open} =
 * {@code open_status == OPEN} e <b>nada mais</b>: o horario publicado e o plano do dia, nao
 * autorizacao. Antes o campo tambem exigia estar dentro da janela, e no aparelho isso produziu
 * o caso que o Andre decidiu: profissional Trabalhando, fila aberta, loja aparecendo FECHADA.
 *
 * <p>Janelas montadas em torno do {@code now} (±1h e futuro) para o teste ser deterministico.
 * O horario continua valendo onde ele e previsao — o auto-fechamento no fim do expediente tem
 * prova propria em {@code AutoCloseAtNightIntegrationTest}.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IsOpenEffectiveIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired TransactionTemplate tx;

    private final Faker faker = new Faker();

    String openWithinId;   // OPEN + horario atual DENTRO da janela  -> is_open true
    String openOutsideId;  // OPEN + horario atual FORA da janela     -> is_open true (regra nova)
    String closedWithinId; // CLOSED + janela cobre agora             -> is_open false

    @BeforeAll
    void setup() throws Exception {
        LocalTime now = BusinessClock.nowTime();

        // Loja 1 — OPEN, janela [now-1h, now+1h): aberta agora
        openWithinId = createEstablishment();
        setHoursAndStatus(openWithinId, OpenStatus.OPEN, now.minusHours(1), now.plusHours(1));

        // Loja 2 — OPEN com a janela no futuro [now+2h, now+3h): aberta assim mesmo
        openOutsideId = createEstablishment();
        setHoursAndStatus(openOutsideId, OpenStatus.OPEN, now.plusHours(2), now.plusHours(3));

        // Loja 3 — janela cobre agora, mas open_status CLOSED: fechada agora
        closedWithinId = createEstablishment();
        setHoursAndStatus(closedWithinId, OpenStatus.CLOSED, now.minusHours(1), now.plusHours(1));

        log.info("[IsOpenEffective] setup — within={} outside={} closedWithin={}",
                openWithinId, openOutsideId, closedWithinId);
    }

    private String createEstablishment() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        return estab.id();
    }

    private void setHoursAndStatus(String id, OpenStatus status, LocalTime opening, LocalTime closing) {
        TestUserUtils.setStatusAndHours(tx, establishmentRepository, id, status, opening, closing);
    }

    @Test
    @Order(10)
    @DisplayName("OPEN dentro da janela -> is_open true")
    void openWithinWindow_isOpenTrue() throws Exception {
        mvc.perform(get("/establishment/id/{id}", openWithinId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andExpect(jsonPath("$.is_open").value(true));
    }

    @Test
    @Order(20)
    @DisplayName("OPEN fora da janela -> is_open true: quem abre e o profissional, nao o relogio")
    void openOutsideWindow_isOpenTrue() throws Exception {
        mvc.perform(get("/establishment/id/{id}", openOutsideId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andExpect(jsonPath("$.is_open").value(true))
                .andExpect(jsonPath("$.opening_time").exists());
    }

    @Test
    @Order(30)
    @DisplayName("CLOSED mesmo dentro da janela -> is_open false")
    void closedWithinWindow_isOpenFalse() throws Exception {
        mvc.perform(get("/establishment/id/{id}", closedWithinId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("CLOSED"))
                .andExpect(jsonPath("$.is_open").value(false));
    }
}
