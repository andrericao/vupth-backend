package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.cut.CutRequestStatus;
import com.ospaulos.vupth.domain.attendance.cut.repository.CutRequestRepository;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * solicitar-agendamento (24/10): o pedido de corte para um dia que o profissional nao liberou. Aceitar abre aquele dia e vira o
 * agendamento; o profissional abrir o dia sem aceitar faz o pedido vencer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleRequestIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CutRequestRepository cutRequestRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();
    private static final String OPEN = "\"closed\":false,\"opens\":\"08:00\",\"closes\":\"20:00\"";

    private String profToken, profId, establishmentId, serviceItemId, clientToken, otherClientToken;
    private LocalDate target; // o dia que a agenda do profissional nao libera

    private static String week(DayOfWeek closedDay) {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (days.length() > 0) days.append(',');
            days.append("{\"day\":\"").append(d).append("\",").append(d == closedDay ? "\"closed\":true" : OPEN).append('}');
        }
        return "{\"days\":[" + days + "]}";
    }

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        profToken = owner.token();
        profId = owner.id();
        establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof Agenda");
        serviceItemId = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)).id();
        clientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        otherClientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        target = BusinessClock.today().plusDays(3);

        mvc.perform(put("/establishment/" + establishmentId + "/hours/weekly").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", profToken).content(week(null))).andExpect(status().isOk());
        // a agenda do profissional libera todos os dias, menos o dia da semana do alvo
        mvc.perform(put("/professional/me/hours/weekly").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", profToken).content(week(target.getDayOfWeek()))).andExpect(status().isOk());
    }

    @AfterEach
    void clean() {
        cutRequestRepository.deleteAll();
        attendanceRepository.deleteAll();
    }

    private String request(String token, LocalDate day, int hour) throws Exception {
        String when = BusinessClock.at(day, LocalTime.of(hour, 0)).toString();
        String body = "{\"professionalId\":\"" + profId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"serviceItemIds\":[\"" + serviceItemId + "\"],\"desiredTime\":\"" + when + "\"}";
        MvcResult r = mvc.perform(post("/attendances/cut-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token).content(body))
                .andExpect(status().isCreated()).andReturn();
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    @Test
    @Order(0)
    @DisplayName("agenda publica: o dia fechado pela agenda dele traz store_open=true e agenda_released=false (o Client mostra Solicitar)")
    void publicAgendaTellsWhyTheDayIsClosed() throws Exception {
        mvc.perform(get("/attendances/intentions/professional/{id}", profId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[?(@.date=='" + target + "')].closed").value(hasItem(true)))
                .andExpect(jsonPath("$.days[?(@.date=='" + target + "')].store_open").value(hasItem(true)))
                .andExpect(jsonPath("$.days[?(@.date=='" + target + "')].agenda_released").value(hasItem(false)))
                .andExpect(jsonPath("$.days[?(@.date=='" + target.plusDays(1) + "')].agenda_released").value(hasItem(true)));
    }

    @Test
    @Order(1)
    @DisplayName("pedido para o dia sem agenda nasce agenda_requested e aparece na lista do profissional; dia aberto, nao")
    void requestForClosedDay() throws Exception {
        String id = request(clientToken, target, 10);
        String openDay = request(otherClientToken, target.plusDays(1), 10);

        mvc.perform(get("/attendances/cut-requests").header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].agenda_requested").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.id=='" + openDay + "')].agenda_requested").value(hasItem(false)));
    }

    @Test
    @Order(2)
    @DisplayName("aceitar abre o dia na agenda do profissional, vira o agendamento (INTENTION) e vence os outros pedidos desse dia")
    void acceptOpensTheDay() throws Exception {
        String first = request(clientToken, target, 10);
        String second = request(otherClientToken, target, 15);

        MvcResult accepted = mvc.perform(patch("/attendances/cut-requests/{id}/accept", first).header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();

        UUID attendanceId = UUID.fromString(new JSONObject(accepted.getResponse().getContentAsString())
                .getString("resulting_attendance_id"));
        assertThat(attendanceRepository.findById(attendanceId).orElseThrow().getStatus()).isEqualTo(AttendanceStatus.INTENTION);
        mvc.perform(get("/professional/" + profId + "/hours"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming[?(@.date=='" + target + "')].closed").value(hasItem(false)));
        assertThat(cutRequestRepository.findById(UUID.fromString(second)).orElseThrow().getStatus())
                .isEqualTo(CutRequestStatus.EXPIRED);
    }

    @Test
    @Order(3)
    @DisplayName("o profissional abre o dia sem aceitar: o pedido vence e o cliente agenda direto")
    void openingTheDayExpiresTheRequest() throws Exception {
        LocalDate another = target.plusDays(1);
        // fecha o dia seguinte so para esta data (excecao) e pede nele
        mvc.perform(put("/professional/me/hours/dates/" + another).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken).content("{\"closed\":true}"))
                .andExpect(status().isOk());
        String id = request(clientToken, another, 11);
        assertThat(cutRequestRepository.findById(UUID.fromString(id)).orElseThrow().isAgendaRequested()).isTrue();

        mvc.perform(put("/professional/me/hours/dates/" + another).contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken).content("{" + OPEN + "}"))
                .andExpect(status().isOk());

        assertThat(cutRequestRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
                .isEqualTo(CutRequestStatus.EXPIRED);
    }
}
