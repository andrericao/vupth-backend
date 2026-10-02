package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.service.AutoCloseService;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — "fechar o estabelecimento à noite" (pendencia de lancamento do Andre, 14/09; pergunta
 * do Partner em 17/09).
 *
 * <p>O comportamento existia e nao tinha prova de ponta a ponta: so havia teste do agendador. Aqui a
 * LOJA fecha <b>sozinha</b> depois do horario do dia — sem ninguem tocar em nada:</p>
 * <ol>
 *   <li>passado o fechamento, o profissional em expediente recebe o aviso e a fila dele fecha
 *       ({@code accepting_new = false}), com uma carencia para responder;</li>
 *   <li>vencida a carencia sem atendimento ativo, ele vira {@code OFF};</li>
 *   <li>como o {@code open_status} da loja deriva dos profissionais, ela fica {@code CLOSED}.</li>
 * </ol>
 * O horario de fechamento e o <b>do dia</b> (#2, 17/09): aqui ele entra como excecao de HOJE, para o
 * teste nao depender do dia da semana em que roda.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AutoCloseAtNightIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired AutoCloseService autoCloseService;
    @Autowired TransactionTemplate tx;

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
     * O expediente de HOJE: fechou {@code minutosAtras} minutos atras, tendo aberto ate duas horas
     * antes disso — e <b>sem nunca virar a meia-noite</b>.
     *
     * <p>⚠️ A abertura era sempre {@code agora - 2h}, e so isso ja quebrava o teste entre 00:00 e
     * 02:00 da MADRUGADA: {@code agora - 2h} cai no dia anterior, a janela fica com
     * {@code closes < opens} e o dominio a le como expediente que VIRA a meia-noite
     * ({@code DayHours.crossesMidnight}). O fechamento de hoje passa a ser o de AMANHA, o
     * {@code mostRecentClosingInstant} nao acha nada e nenhum aviso vence — a lista volta vazia.
     * Nao e regressao do produto: aparece so na maquina de quem roda de madrugada, e no CI (UTC)
     * passa verde. Ancorar a abertura na meia-noite tira a hora do relogio da conta.</p>
     */
    private void closedTodayAt(int minutosAtras) throws Exception {
        LocalTime now = BusinessClock.nowTime();
        LocalTime closes = now.minusMinutes(minutosAtras).withSecond(0).withNano(0);

        // Nos primeiros minutos do dia nao existe "fechou ha pouco, ainda hoje" para descrever: o
        // fechamento cairia na vespera. Sao ~6 minutos por dia — melhor pular dizendo por que do que
        // montar uma janela que nao e a do cenario.
        Assumptions.assumeTrue(closes.isAfter(LocalTime.MIDNIGHT),
                "cenario impossivel entre 00:00 e 00:0" + minutosAtras + " — o fechamento cairia ontem");

        LocalTime opens = closes.isAfter(LocalTime.of(2, 0)) ? closes.minusHours(2) : LocalTime.MIDNIGHT;
        String body = String.format("{\"closed\": false, \"opens\": \"%s\", \"closes\": \"%s\"}",
                opens, closes);
        mvc.perform(put("/establishment/{id}/hours/dates/{date}", establishmentId, BusinessClock.today())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(body))
                .andExpect(status().isOk());
    }

    private void weekOpenAllDay() throws Exception {
        StringBuilder days = new StringBuilder();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            days.append(String.format("{\"day\":\"%s\",\"closed\":false,\"opens\":\"00:00\",\"closes\":\"00:00\"},", day));
        }
        mvc.perform(put("/establishment/{id}/hours/weekly", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"days\":[" + days.substring(0, days.length() - 1) + "]}"))
                .andExpect(status().isOk());
    }

    private Professional reload() {
        return professionalRepository.findById(UUID.fromString(ownerId)).orElseThrow();
    }

    private String openStatusOfStore() throws Exception {
        return mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll("(?s).*\"open_status\"\\s*:\\s*\"([A-Z_]+)\".*", "$1");
    }

    @Test
    @Order(1)
    @DisplayName("a loja abre quando o profissional começa a trabalhar, dentro do horário")
    void storeOpensWhenProfessionalStartsWorking() throws Exception {
        weekOpenAllDay();

        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(jsonPath("$.open_status").value("OPEN"))
                .andExpect(jsonPath("$.is_open").value(true))
                .andDo(print());
    }

    @Test
    @Order(2)
    @DisplayName("passado o fechamento do dia, o aviso sai e a fila do profissional fecha")
    void afterClosingTheQueueCloses() throws Exception {
        closedTodayAt(5);

        var prompted = autoCloseService.promptDueClosings();

        assertThat(prompted).as("um aviso para o profissional em expediente")
                .anyMatch(p -> p.professionalId().equals(UUID.fromString(ownerId)));
        Professional professional = reload();
        assertThat(professional.getAcceptingNew()).as("fila fechada para novos").isFalse();
        assertThat(professional.getClosingGraceUntil()).as("carência armada").isNotNull();
        assertThat(professional.getWorkStatus()).as("ainda em expediente durante a carência")
                .isEqualTo(ProfessionalStatus.WORKING);
        assertThat(openStatusOfStore()).as("a loja ainda está aberta na carência").isEqualTo("OPEN");
    }

    @Test
    @Order(3)
    @DisplayName("vencida a carência, o profissional vira OFF e a LOJA fica CLOSED sozinha")
    void afterGraceTheStoreClosesByItself() throws Exception {
        tx.executeWithoutResult(ignored -> {
            Professional professional = reload();
            professional.setClosingGraceUntil(OffsetDateTime.now().minusMinutes(1));
            professionalRepository.save(professional);
        });

        var outcomes = autoCloseService.applyDueGraces();

        assertThat(outcomes).anyMatch(o -> o.professionalId().equals(UUID.fromString(ownerId)) && o.autoOff());
        assertThat(reload().getWorkStatus()).isEqualTo(ProfessionalStatus.OFF);
        assertThat(openStatusOfStore()).as("ninguém tocou em nada e a loja fechou").isEqualTo("CLOSED");

        mvc.perform(get("/establishment/id/{id}", establishmentId))
                .andExpect(jsonPath("$.open_status").value("CLOSED"))
                .andExpect(jsonPath("$.is_open").value(false))
                .andDo(print());
    }

    @Test
    @Order(4)
    @DisplayName("dia fechado não tem fechamento: nenhum aviso sai")
    void closedDayHasNoClosingEvent() throws Exception {
        tx.executeWithoutResult(ignored -> {
            Establishment establishment = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
            establishment.setOpenStatus(OpenStatus.OPEN);
            establishmentRepository.save(establishment);
        });
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/establishment/{id}/hours/dates/{date}", establishmentId, BusinessClock.today())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"closed\": true}"))
                .andExpect(status().isOk());

        var prompted = autoCloseService.promptDueClosings();

        assertThat(prompted).as("dia fechado não dispara o aviso")
                .noneMatch(p -> p.professionalId().equals(UUID.fromString(ownerId)));
    }

    @Test
    @Order(5)
    @DisplayName("URGENTE 27/09: loja com fechamento de hoje ja passado e o resto da semana em 24h nao derruba o proximo evento (NPE)")
    void noNextClosing_doesNotBreakTheLoop() throws Exception {
        // a semana inteira em 24h (sem fechamento) e hoje com fechamento ja passado: o hasClosingEvent
        // aceita a loja (ve o de hoje) e o nextClosingInstant devolve nulo (nada no futuro)
        weekOpenAllDay();
        closedTodayAt(30);
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"status\":\"ON_BREAK\"}"))
                .andExpect(status().isOk());
        // Fila fechada e uma carencia ja armada: o aviso nao esta devido, entao o candidato desta loja vem do
        // proximo fechamento — o nulo — e o "mais cedo" ja comeca preenchido pela carencia (em producao,
        // qualquer profissional com carencia pendente). Era a combinacao que dava o NPE.
        tx.executeWithoutResult(ignored -> {
            Professional p = professionalRepository.findById(UUID.fromString(ownerId)).orElseThrow();
            p.setAcceptingNew(false);
            p.setClosingGraceUntil(java.time.OffsetDateTime.now().plusHours(1));
            professionalRepository.save(p);
        });

        org.assertj.core.api.Assertions.assertThatCode(() -> autoCloseService.nextEventAt())
                .as("o laco passa por todas as lojas — uma sem fechamento futuro nao pode quebrar as outras")
                .doesNotThrowAnyException();

        // e o que o app viu: mudar de status (que reagenda o timer) nao da 500
        mvc.perform(patch("/professional/me/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"status\":\"ON_BREAK\"}"))
                .andExpect(status().isOk());
    }
}
