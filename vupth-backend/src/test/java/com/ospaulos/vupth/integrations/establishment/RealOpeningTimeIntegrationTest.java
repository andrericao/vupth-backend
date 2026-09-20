package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.valueObject.ProfessionalStatus;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.shared.BusinessClock;
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
import java.time.LocalTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — <b>o dia passa a valer a hora em que a loja REALMENTE abriu</b> (decisao do Andre,
 * 19/09; pedido do Client de 17/09).
 *
 * <p>Quem abre a loja e a chegada do primeiro profissional, nao o relogio (D11). Entao o
 * {@code opening_time} que o cliente le e a hora em que esse profissional marcou "trabalhando":
 * cadastrado 08:00 e o primeiro chega 07:30, aquele dia abriu 07:30. Nao ha registro novo — escreve-se
 * a <b>excecao por data</b> que o horario por dia (#2) ja tinha, e o contrato nao ganha campo.</p>
 *
 * <p>As guardas tambem sao testadas, porque cada uma existe por um motivo que aparece em producao:
 * a segunda chegada do dia nao reescreve a primeira (loja que fecha ao meio-dia e reabre as 14h
 * continua tendo aberto de manha), e o {@code closes} do plano sobrevive — e ele que guia o
 * auto-fechamento.</p>
 *
 * <p>⚠️ O teste monta o horario de HOJE por excecao e ancora tudo na hora de parede, sem nunca virar
 * a meia-noite — a licao do {@code AutoCloseAtNightIntegrationTest}, que reprovava so de madrugada.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RealOpeningTimeIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    private String ownerToken, ownerId, establishmentId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.ownerId = owner.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Dono Profissional");
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(ownerId)));
        TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
    }

    /**
     * A semana inteira com uma janela que CONTEM a hora de agora e <b>nao vira a meia-noite</b>: uma
     * hora para cada lado, mas grudada nas bordas do dia quando agora esta perto delas. Foi o que
     * fez este teste ser pulado as 23h53 na primeira execucao — {@code agora + 1h} caia no dia
     * seguinte. Com as bordas, so o ultimo minuto do dia fica de fora.
     */
    private void weekAround(LocalTime agora) throws Exception {
        LocalTime abre = agora.isBefore(LocalTime.of(1, 0))
                ? LocalTime.MIDNIGHT
                : agora.minusHours(1).withSecond(0).withNano(0);
        LocalTime fecha = agora.isAfter(LocalTime.of(22, 59))
                ? LocalTime.of(23, 59)
                : agora.plusHours(1).withSecond(0).withNano(0);
        StringBuilder days = new StringBuilder();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            days.append(String.format("{\"day\":\"%s\",\"closed\":false,\"opens\":\"%s\",\"closes\":\"%s\"},",
                    day, abre, fecha));
        }
        mvc.perform(put("/establishment/{id}/hours/weekly", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"days\":[" + days.substring(0, days.length() - 1) + "]}"))
                .andExpect(status().isOk());
    }

    private void setStatus(ProfessionalStatus status) throws Exception {
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"status\":\"" + status.name() + "\"}"))
                .andExpect(status().isOk());
    }

    private String openingTimeOfToday() throws Exception {
        String body = mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("opening_time").asText();
    }

    private String closingTimeOfToday() throws Exception {
        String body = mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("closing_time").asText();
    }

    @Test
    @Order(10)
    @DisplayName("a chegada do primeiro profissional vira o opening_time do dia")
    void firstArrivalBecomesTodayOpening() throws Exception {
        LocalTime agora = BusinessClock.nowTime();
        // Sobra so o ultimo minuto do dia: as 23:59 nao ha fechamento DEPOIS de agora para a janela
        // ficar do lado de ca da meia-noite, e a 00:00 a abertura do plano coincidiria com a chegada.
        Assumptions.assumeTrue(agora.isBefore(LocalTime.of(23, 59)) && agora.isAfter(LocalTime.MIDNIGHT),
                "cenario impossivel no ultimo minuto do dia — a janela viraria a meia-noite");
        weekAround(agora);

        String planejado = openingTimeOfToday();
        String fechamentoPlanejado = closingTimeOfToday();

        setStatus(ProfessionalStatus.WORKING);

        String real = openingTimeOfToday();
        Assertions.assertNotEquals(planejado, real,
                "o dia devia passar a valer a hora da chegada, nao a cadastrada");
        Assertions.assertEquals(agora.withSecond(0).withNano(0).toString().substring(0, 5), real.substring(0, 5),
                "o opening_time do dia e a hora em que o profissional marcou trabalhando");
        Assertions.assertEquals(fechamentoPlanejado, closingTimeOfToday(),
                "o fechamento do plano fica — e ele que guia o auto-fechamento");

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andExpect(jsonPath("$.is_open").value(true))
                .andDo(print());
    }

    @Test
    @Order(20)
    @DisplayName("a segunda chegada do dia NAO reescreve a primeira")
    void secondArrivalKeepsTheFirst() throws Exception {
        String depoisDaPrimeiraChegada = openingTimeOfToday();

        setStatus(ProfessionalStatus.OFF);   // loja fecha
        setStatus(ProfessionalStatus.WORKING); // e reabre mais tarde, no mesmo dia

        Assertions.assertEquals(depoisDaPrimeiraChegada, openingTimeOfToday(),
                "a loja que fecha e reabre no mesmo dia continua tendo aberto na primeira chegada");
    }

    @Test
    @Order(30)
    @DisplayName("dia sem horario configurado nao ganha janela por causa da chegada")
    void notConfiguredStaysNotConfigured() throws Exception {
        var outro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String outraLoja = TestUserUtils.becomeOwner(mvc, mapper, faker, outro.token()).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, outro.token(), outraLoja, "Sem Horario");
        TestUserUtils.createServiceItem(mvc, mapper, outraLoja, outro.token(),
                ServiceItemType.BARBA, "Barba", BigDecimal.valueOf(20));

        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", outro.token())
                        .content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isOk());

        // Sem horario cadastrado, "qualquer hora vale": nao ha janela a estreitar, e inventar uma
        // passaria a limitar refeicao e intencao de fila de uma loja que nunca configurou nada.
        mvc.perform(get("/establishment/id/{id}", outraLoja))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andExpect(jsonPath("$.opening_time").doesNotExist())
                .andDo(print());
    }
}
