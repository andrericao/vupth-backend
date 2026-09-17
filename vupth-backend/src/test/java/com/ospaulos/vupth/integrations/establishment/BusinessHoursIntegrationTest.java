package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — horario de funcionamento por dia (#2).
 *
 *   GET    /establishment/{id}/hours               (publico)
 *   PUT    /establishment/{id}/hours/weekly        (dono)
 *   PUT    /establishment/{id}/hours/dates/{data}  (dono)
 *   DELETE /establishment/{id}/hours/dates/{data}  (dono)
 *
 * Modelo (Andre, 16/09): semana com os 7 dias (horario ou fechado); excecao por data; o horario de
 * uma data e a excecao dela, senao o dia da semana; o passado nao se edita.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessHoursIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    private String ownerToken, establishmentId, otherOwnerToken, clientToken;
    private LocalDate nextMonday;

    private static final String WEEK = """
            { "days": [
              { "day": "MONDAY",    "closed": false, "opens": "08:00", "closes": "18:00" },
              { "day": "TUESDAY",   "closed": false, "opens": "08:00", "closes": "18:00" },
              { "day": "WEDNESDAY", "closed": false, "opens": "08:00", "closes": "18:00" },
              { "day": "THURSDAY",  "closed": false, "opens": "08:00", "closes": "18:00" },
              { "day": "FRIDAY",    "closed": false, "opens": "08:00", "closes": "18:00" },
              { "day": "SATURDAY",  "closed": false, "opens": "08:00", "closes": "12:00" },
              { "day": "SUNDAY",    "closed": true }
            ] }
            """;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken).id();

        var other = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeOwner(mvc, mapper, faker, other.token());
        this.otherOwnerToken = other.token();

        this.clientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        this.nextMonday = BusinessClock.today().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }

    private String hoursUrl() {
        return "/establishment/" + establishmentId + "/hours";
    }

    @Test
    @Order(1)
    @DisplayName("loja recem-criada: nao configurada, semana e proximos dias vazios")
    void newEstablishment_notConfigured() throws Exception {
        mvc.perform(get(hoursUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.weekly", hasSize(0)))
                .andExpect(jsonPath("$.upcoming", hasSize(0)))
                .andDo(print());
    }

    @Test
    @Order(2)
    @DisplayName("excecao antes de definir a semana: 422")
    void exceptionBeforeWeek_422() throws Exception {
        mvc.perform(put(hoursUrl() + "/dates/" + nextMonday)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"closed\": true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_INVALID"));
    }

    @Test
    @Order(3)
    @DisplayName("semana sem os 7 dias: 422, nada gravado")
    void incompleteWeek_422() throws Exception {
        mvc.perform(put(hoursUrl() + "/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"days\":[{\"day\":\"MONDAY\",\"closed\":false,\"opens\":\"08:00\",\"closes\":\"18:00\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_INVALID"));

        mvc.perform(get(hoursUrl())).andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @Order(4)
    @DisplayName("dia com horario E fechado: 422")
    void closedWithHours_422() throws Exception {
        String bad = WEEK.replace("{ \"day\": \"SUNDAY\",    \"closed\": true }",
                "{ \"day\": \"SUNDAY\", \"closed\": true, \"opens\": \"08:00\", \"closes\": \"12:00\" }");
        mvc.perform(put(hoursUrl() + "/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(bad))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_INVALID"));
    }

    @Test
    @Order(5)
    @DisplayName("sem token: 401 · cliente: 403 · dono de outra loja: 404")
    void accessControl() throws Exception {
        mvc.perform(put(hoursUrl() + "/weekly").contentType(MediaType.APPLICATION_JSON).content(WEEK))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(hoursUrl() + "/weekly").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken).content(WEEK))
                .andExpect(status().isForbidden());
        mvc.perform(put(hoursUrl() + "/weekly").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", otherOwnerToken).content(WEEK))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(10)
    @DisplayName("PUT semana: 200, a semana na ordem e os 14 proximos dias resolvidos")
    void putWeek_ok() throws Exception {
        mvc.perform(put(hoursUrl() + "/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(WEEK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.weekly", hasSize(7)))
                .andExpect(jsonPath("$.weekly[0].day").value("MONDAY"))
                .andExpect(jsonPath("$.weekly[0].opens").value("08:00"))
                .andExpect(jsonPath("$.weekly[0].closes").value("18:00"))
                .andExpect(jsonPath("$.weekly[5].closes").value("12:00"))
                .andExpect(jsonPath("$.weekly[6].closed").value(true))
                .andExpect(jsonPath("$.weekly[6].opens").doesNotExist())
                .andExpect(jsonPath("$.upcoming", hasSize(14)))
                .andExpect(jsonPath("$.upcoming[0].date").value(BusinessClock.today().toString()))
                .andDo(print());
    }

    @Test
    @Order(11)
    @DisplayName("GET publico devolve o que foi gravado")
    void getIsPublic() throws Exception {
        mvc.perform(get(hoursUrl()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.weekly[6].day").value("SUNDAY"));
    }

    @Test
    @Order(20)
    @DisplayName("excecao so na proxima segunda: vale naquela data e em mais nenhuma")
    void putException_onlyThatDate() throws Exception {
        mvc.perform(put(hoursUrl() + "/dates/" + nextMonday)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"closed\": false, \"opens\": \"10:00\", \"closes\": \"14:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceptions", hasSize(1)))
                .andExpect(jsonPath("$.exceptions[0].date").value(nextMonday.toString()))
                .andExpect(jsonPath("$.exceptions[0].opens").value("10:00"))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].opens", nextMonday).value("10:00"))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].exception", nextMonday).value(true))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].opens", nextMonday.plusWeeks(1)).value("08:00"))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].exception", nextMonday.plusWeeks(1)).value(false))
                .andDo(print());
    }

    @Test
    @Order(21)
    @DisplayName("trocar a semana mantem a excecao da data")
    void weekChange_keepsException() throws Exception {
        mvc.perform(put(hoursUrl() + "/weekly")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content(WEEK.replaceFirst("\"opens\": \"08:00\", \"closes\": \"18:00\"",
                                "\"opens\": \"07:00\", \"closes\": \"15:00\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekly[0].opens").value("07:00"))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].opens", nextMonday).value("10:00"))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].opens", nextMonday.plusWeeks(1)).value("07:00"));
    }

    @Test
    @Order(22)
    @DisplayName("excecao pode ser fechado")
    void closedException() throws Exception {
        mvc.perform(put(hoursUrl() + "/dates/" + nextMonday.plusDays(1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"closed\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].closed", nextMonday.plusDays(1)).value(true));
    }

    @Test
    @Order(23)
    @DisplayName("data passada: 422 para criar e para remover")
    void pastDate_422() throws Exception {
        LocalDate yesterday = BusinessClock.today().minusDays(1);
        mvc.perform(put(hoursUrl() + "/dates/" + yesterday)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"closed\": true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_INVALID"));
        mvc.perform(delete(hoursUrl() + "/dates/" + yesterday).header("Authorization", ownerToken))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @Order(24)
    @DisplayName("DELETE da excecao: 204, e a data volta ao dia da semana")
    void deleteException() throws Exception {
        mvc.perform(delete(hoursUrl() + "/dates/" + nextMonday).header("Authorization", ownerToken))
                .andExpect(status().isNoContent());

        mvc.perform(get(hoursUrl()))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].opens", nextMonday).value("07:00"))
                .andExpect(jsonPath("$.upcoming[?(@.date == '%s')].exception", nextMonday).value(false))
                .andExpect(jsonPath("$.exceptions", hasSize(1)));
    }

    @Test
    @Order(30)
    @DisplayName("general-infos com horario (app antigo): vale para os 7 dias e mantem as excecoes")
    void generalInfos_appliesToWholeWeek() throws Exception {
        mvc.perform(patch("/establishment/{id}/general-infos", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"opening_time\":\"09:00\",\"closing_time\":\"19:00\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(hoursUrl()))
                .andExpect(jsonPath("$.weekly[0].opens").value("09:00"))
                .andExpect(jsonPath("$.weekly[6].closed").value(false))
                .andExpect(jsonPath("$.weekly[6].closes").value("19:00"))
                .andExpect(jsonPath("$.exceptions", hasSize(1)))
                .andDo(print());
    }
}
