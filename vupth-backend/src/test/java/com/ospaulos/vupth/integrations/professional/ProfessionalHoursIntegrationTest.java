package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.CreateIntentionRequest;
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
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — agenda do profissional (agenda-do-profissional, Andre 05/10).
 *
 *   GET    /professional/{id}/hours                (publico; upcoming ja efetivo: loja ∩ ele)
 *   PUT    /professional/me/hours/weekly           (o proprio profissional)
 *   PUT    /professional/me/hours/dates/{data}     (o proprio profissional)
 *   DELETE /professional/me/hours/dates/{data}     (o proprio profissional)
 *
 * E o efeito no agendamento: intencao fora da agenda dele = 422 INTENTION_OUTSIDE_PROFESSIONAL_HOURS, e a
 * agenda publica traz {@code days} com o horario efetivo.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProfessionalHoursIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();
    private final List<UUID> createdIntentions = new ArrayList<>();

    private String profToken, profId, establishmentId, clientToken;
    private List<UUID> serviceItemIds;
    private LocalDate nextMonday;

    private static String week(String weekday, String weekend) {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            String hours = d.getValue() <= 5 ? weekday : weekend;
            if (days.length() > 0) days.append(',');
            days.append("{\"day\":\"").append(d).append("\",").append(hours).append('}');
        }
        return "{\"days\":[" + days + "]}";
    }

    private static final String OPEN_08_20 = "\"closed\":false,\"opens\":\"08:00\",\"closes\":\"20:00\"";
    private static final String OPEN_09_17 = "\"closed\":false,\"opens\":\"09:00\",\"closes\":\"17:00\"";
    private static final String OPEN_07_17 = "\"closed\":false,\"opens\":\"07:00\",\"closes\":\"17:00\"";
    private static final String CLOSED = "\"closed\":true";

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.profId = owner.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof Agenda");
        var item = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(item.id()));
        this.clientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        this.nextMonday = BusinessClock.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        // A loja: 08-20 todos os dias.
        mvc.perform(put("/establishment/" + establishmentId + "/hours/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(week(OPEN_08_20, OPEN_08_20)))
                .andExpect(status().isOk());
    }

    @AfterAll
    void cleanup() {
        attendanceRepository.deleteAllById(createdIntentions);
    }

    private String hoursUrl() {
        return "/professional/" + profId + "/hours";
    }

    @Test
    @Order(1)
    @DisplayName("sem agenda propria: nao configurada, e o upcoming efetivo e o horario da loja")
    void noOwnHours_upcomingIsStore() throws Exception {
        mvc.perform(get(hoursUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.weekly", hasSize(0)))
                .andExpect(jsonPath("$.upcoming[0].opens").value("08:00"))
                .andExpect(jsonPath("$.upcoming[0].closes").value("20:00"));
    }

    @Test
    @Order(2)
    @DisplayName("agenda que passa do horario da loja: 422 PROFESSIONAL_HOURS_OUTSIDE_ESTABLISHMENT, nada gravado")
    void outsideStore_422() throws Exception {
        mvc.perform(put("/professional/me/hours/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(week(OPEN_07_17, CLOSED)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_HOURS_OUTSIDE_ESTABLISHMENT"));

        mvc.perform(get(hoursUrl())).andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @Order(3)
    @DisplayName("cliente sem perfil profissional nao edita agenda: 403")
    void clientCannotEdit_403() throws Exception {
        mvc.perform(put("/professional/me/hours/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(week(OPEN_09_17, CLOSED)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(10)
    @DisplayName("semana 09-17 nos dias uteis, fechado no fim de semana: 200, e o GET publico mostra")
    void putWeek_200() throws Exception {
        mvc.perform(put("/professional/me/hours/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content(week(OPEN_09_17, CLOSED)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.weekly", hasSize(7)));

        mvc.perform(get(hoursUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.upcoming", hasSize(14)));
    }

    @Test
    @Order(20)
    @DisplayName("intencao fora da agenda dele (18:00, loja aberta): 422 INTENTION_OUTSIDE_PROFESSIONAL_HOURS")
    void intentionOutsideProfessionalHours_422() throws Exception {
        mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CreateIntentionRequest(UUID.fromString(profId),
                                serviceItemIds, BusinessClock.at(nextMonday, LocalTime.of(18, 30))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_OUTSIDE_PROFESSIONAL_HOURS"));
    }

    @Test
    @Order(21)
    @DisplayName("intencao dentro da agenda dele (10:00): 201")
    void intentionInsideProfessionalHours_201() throws Exception {
        MvcResult r = mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CreateIntentionRequest(UUID.fromString(profId),
                                serviceItemIds, BusinessClock.at(nextMonday, LocalTime.of(10, 0))))))
                .andExpect(status().isCreated())
                .andReturn();
        createdIntentions.add(UUID.fromString(new JSONObject(r.getResponse().getContentAsString()).getString("id")));
    }

    @Test
    @Order(30)
    @DisplayName("agenda publica: days traz o horario efetivo, de hoje ate 30 dias (fim de semana fechado)")
    void publicAgendaDays() throws Exception {
        mvc.perform(get("/attendances/intentions/professional/{id}", profId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days", hasSize(31)))
                .andExpect(result -> {
                    var days = new JSONObject(result.getResponse().getContentAsString()).getJSONArray("days");
                    for (int i = 0; i < days.length(); i++) {
                        var d = days.getJSONObject(i);
                        DayOfWeek dow = LocalDate.parse(d.getString("date")).getDayOfWeek();
                        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
                            Assertions.assertTrue(d.getBoolean("closed"), "fim de semana fechado: " + d);
                        } else {
                            Assertions.assertEquals("09:00", d.getString("opens"), d.toString());
                            Assertions.assertEquals("17:00", d.getString("closes"), d.toString());
                        }
                    }
                });
    }

    @Test
    @Order(40)
    @DisplayName("folga numa data: PUT fecha o dia (a intencao nele vira 422) e o DELETE devolve o da semana")
    void dateException_putAndDelete() throws Exception {
        String date = nextMonday.plusDays(7).toString();
        mvc.perform(put("/professional/me/hours/dates/" + date)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"closed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions[0].date").value(date))
                .andExpect(jsonPath("$.exceptions[0].closed").value(true));

        mvc.perform(post("/attendances/intentions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content(mapper.writeValueAsString(new CreateIntentionRequest(UUID.fromString(profId),
                                serviceItemIds, BusinessClock.at(nextMonday.plusDays(7), LocalTime.of(10, 0))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_OUTSIDE_PROFESSIONAL_HOURS"));

        mvc.perform(delete("/professional/me/hours/dates/" + date).header("Authorization", profToken))
                .andExpect(status().isNoContent());
        mvc.perform(get(hoursUrl())).andExpect(jsonPath("$.exceptions", hasSize(0)));
    }

    @Test
    @Order(41)
    @DisplayName("excecao numa data que passa do horario da loja: 422")
    void dateExceptionOutsideStore_422() throws Exception {
        mvc.perform(put("/professional/me/hours/dates/" + nextMonday.plusDays(7))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", profToken)
                        .content("{\"closed\": false, \"opens\": \"06:00\", \"closes\": \"12:00\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_HOURS_OUTSIDE_ESTABLISHMENT"));
    }

    @Test
    @Order(50)
    @DisplayName("PUT sem token: 401")
    void noToken_401() throws Exception {
        mvc.perform(put("/professional/me/hours/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(week(OPEN_09_17, CLOSED)))
                .andExpect(status().isUnauthorized());
    }
}
