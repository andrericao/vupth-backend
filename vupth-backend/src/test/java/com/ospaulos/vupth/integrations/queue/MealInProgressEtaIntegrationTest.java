package com.ospaulos.vupth.integrations.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test — quem chega durante a REFEICAO EM CURSO espera o fim dela, e nao "agora".
 *
 * <p>O defeito (Partner 18/09, Client 17/09 e 25/09): com o profissional almocando, o cliente que
 * entrava na fila lia espera 0. Eram dois furos somados — a simulacao devolvia 0 para o 1o da fila
 * sem olhar a refeicao em curso, e o bloco da refeicao em curso nao levava id, entao nunca entrava
 * no lock do ingresso e o {@code wait-time} a esquecia em qualquer posicao.
 *
 * <p>Os dois caminhos de entrada do relato: a <b>entrada direta</b> (walk-in, loja aberta) e o
 * <b>aceite do pedido de corte</b> (loja fechada). O restante da refeicao e medido em minutos
 * inteiros, entao aceita-se {@code MEAL - 1} pelo segundo que passa entre iniciar e ler.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(
        classes = VupthApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MealInProgressEtaIntegrationTest {

    private static final int MEAL_MINUTES = 25;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository intervalRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired CutRequestRepository cutRequestRepository;

    private final Faker faker = new Faker();

    /** Um profissional dono da propria loja, com o servico e o token. */
    private record Shop(UUID profId, UUID establishmentId, String profToken, UUID serviceItemId) {}

    private record Customer(UUID id, String token) {}

    Shop walkInShop, cutShop;
    Customer clientA, clientB, cutClient;
    String attendanceIdA;

    @BeforeAll
    void setup() throws Exception {
        this.walkInShop = shop();
        // Walk-in so entra com a loja aberta e o profissional em expediente (portoes C1 e 24/09).
        TestUserUtils.openEstablishment(establishmentRepository, walkInShop.establishmentId().toString());
        TestUserUtils.startShift(professionalRepository, walkInShop.establishmentId().toString());

        // Loja FECHADA (default): e o que permite pedir corte.
        this.cutShop = shop();

        this.clientA = customer();
        this.clientB = customer();
        this.cutClient = customer();
    }

    private Shop shop() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id());
        var service = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        return new Shop(UUID.fromString(owner.id()), UUID.fromString(estab.id()), owner.token(),
                UUID.fromString(service.id()));
    }

    private Customer customer() throws Exception {
        var c = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        return new Customer(UUID.fromString(c.id()), c.token());
    }

    /** Isola UMA refeicao controlada e a inicia agora: termina em now + MEAL_MINUTES. */
    private void startMeal(Shop shop) throws Exception {
        intervalRepository.deleteAll(intervalRepository.findAll().stream()
                .filter(i -> i.getProfessional().getId().equals(shop.profId()))
                .toList());

        MvcResult meal = mvc.perform(post("/api/v1/professionals/{pid}/intervals/recurring", shop.profId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", shop.profToken())
                        .content("{\"durationMinutes\":" + MEAL_MINUTES
                                + ",\"fixedStartTime\":\"00:01:00Z\",\"recurrencePattern\":\"DAILY\""
                                + ",\"description\":\"Almoco\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String mealId = new JSONObject(meal.getResponse().getContentAsString()).getString("intervalId");

        mvc.perform(patch("/api/v1/professionals/{pid}/intervals/{iid}/start", shop.profId(), mealId)
                        .header("Authorization", shop.profToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    private String enqueue(Shop shop, Customer customer, int scheduledInMinutes) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                shop.profId(), shop.establishmentId(), customer.id(), List.of(shop.serviceItemId()),
                AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(scheduledInMinutes), 30,
                OffsetDateTime.now());
        MvcResult created = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", customer.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return new JSONObject(created.getResponse().getContentAsString()).getString("id");
    }

    private int waitTime(String attendanceId, Customer customer) throws Exception {
        MvcResult res = mvc.perform(get("/client-queue/{attendanceId}/wait-time", attendanceId)
                        .param("clientId", customer.id().toString())
                        .header("Authorization", customer.token()))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONObject(res.getResponse().getContentAsString()).getInt("estimatedWaitingTime");
    }

    @Test
    @Order(10)
    @DisplayName("Fila vazia + refeicao em curso: o 1o da fila espera o restante da refeicao, nao 'agora'")
    void walkIn_firstInQueue_waitsForMeal() throws Exception {
        startMeal(walkInShop);

        this.attendanceIdA = enqueue(walkInShop, clientA, 1);

        assertThat(waitTime(attendanceIdA, clientA))
                .as("ninguem a frente, mas o profissional esta almocando")
                .isBetween(com.ospaulos.vupth.domain.queue.service.QueueUtils.displayedWait(MEAL_MINUTES - 1), com.ospaulos.vupth.domain.queue.service.QueueUtils.displayedWait(MEAL_MINUTES)); // folga de -10 (S10)
    }

    @Test
    @Order(20)
    @DisplayName("Quem entra ATRAS de alguem tambem conta a refeicao em curso (antes via so o atendimento a frente)")
    void walkIn_behindSomeone_countsMealToo() throws Exception {
        String attendanceIdB = enqueue(walkInShop, clientB, 2);
        int aheadMinutes = attendanceRepository.findById(UUID.fromString(attendanceIdA))
                .orElseThrow().getEstimatedTotalMinutes();

        assertThat(waitTime(attendanceIdB, clientB))
                .as("atendimento de A (%d min) + o restante da refeicao", aheadMinutes)
                .isBetween(com.ospaulos.vupth.domain.queue.service.QueueUtils.displayedWait(aheadMinutes + MEAL_MINUTES - 1), com.ospaulos.vupth.domain.queue.service.QueueUtils.displayedWait(aheadMinutes + MEAL_MINUTES)); // folga de -10 (S10)
    }

    @Test
    @Order(30)
    @DisplayName("Pedido de corte aceito durante a refeicao: a estimativa parte do fim dela (o relato de 17/09)")
    void cutRequestAccepted_duringMeal_waitsForMeal() throws Exception {
        startMeal(cutShop);

        String body = "{\"professionalId\":\"" + cutShop.profId() + "\",\"establishmentId\":\""
                + cutShop.establishmentId() + "\",\"serviceItemIds\":[\"" + cutShop.serviceItemId() + "\"]}";
        MvcResult requested = mvc.perform(post("/attendances/cut-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", cutClient.token())
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        String cutRequestId = new JSONObject(requested.getResponse().getContentAsString()).getString("id");

        MvcResult accepted = mvc.perform(patch("/attendances/cut-requests/{id}/accept", cutRequestId)
                        .header("Authorization", cutShop.profToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        String attendanceId = new JSONObject(accepted.getResponse().getContentAsString())
                .getString("resulting_attendance_id");

        assertThat(waitTime(attendanceId, cutClient))
                .as("o encaixe aceito com o profissional almocando nao e 'agora'")
                .isBetween(com.ospaulos.vupth.domain.queue.service.QueueUtils.displayedWait(MEAL_MINUTES - 1), com.ospaulos.vupth.domain.queue.service.QueueUtils.displayedWait(MEAL_MINUTES)); // folga de -10 (S10)
    }

    @AfterAll
    void tearDown() {
        cutRequestRepository.deleteAll(); // FK resulting_attendance_id -> apaga o pedido antes
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
