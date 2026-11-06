package com.ospaulos.vupth.integrations.professional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * abrir-dia-sem-semana (Andre, 06/11): abrir o dia nao e disponibilizar a agenda. Sem a semana montada, o profissional abre o dia,
 * o cliente so solicita e o aceite vira o agendamento; com a semana montada, o cliente agenda direto.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenDayWithoutWeekIntegrationTest {

    private static final String OPEN = "\"closed\":false,\"opens\":\"08:00\",\"closes\":\"20:00\"";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private final Faker faker = new Faker();

    private static String week() {
        StringBuilder days = new StringBuilder();
        for (DayOfWeek d : DayOfWeek.values()) {
            if (days.length() > 0) days.append(',');
            days.append("{\"day\":\"").append(d).append("\",").append(OPEN).append('}');
        }
        return "{\"days\":[" + days + "]}";
    }

    @Test
    @DisplayName("sem semana: abre o dia, agenda_released falso, agendar 422, solicitar e aceitar vira agendamento; montou a semana, agenda direto")
    void openDayThenRequestThenWeek() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String token = owner.token();
        String establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, token).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, token, establishmentId, "Prof Sem Semana");
        String itemId = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, token,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)).id();
        String clientToken = TestUserUtils.registerAndLoginUser(mvc, mapper, faker).token();
        mvc.perform(put("/establishment/" + establishmentId + "/hours/weekly").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token).content(week())).andExpect(status().isOk());
        LocalDate day = BusinessClock.today().plusDays(3);

        // abre o dia sem a semana; fora do horario da loja continua 422; repetir a padrao precisa da semana
        mvc.perform(post("/professional/me/hours/open").contentType(MediaType.APPLICATION_JSON).header("Authorization", token)
                        .content("{\"dates\":[{\"date\":\"" + day + "\",\"opens\":\"10:00\",\"closes\":\"13:00\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.exceptions[0].date").value(day.toString()));
        mvc.perform(post("/professional/me/hours/open").contentType(MediaType.APPLICATION_JSON).header("Authorization", token)
                        .content("{\"dates\":[{\"date\":\"" + day.plusDays(1) + "\",\"opens\":\"06:00\",\"closes\":\"12:00\"}]}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/professional/me/hours/open").contentType(MediaType.APPLICATION_JSON).header("Authorization", token)
                        .content("{\"repeat_default\":true,\"from\":\"" + day + "\",\"to\":\"" + day.plusDays(2) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_HOURS_INVALID"));

        // a agenda do profissional e a publica dizem que o dia esta aberto, mas sem agenda disponivel
        mvc.perform(get("/professional/me/agenda").param("from", day.toString()).param("to", day.toString())
                        .header("Authorization", token))
                .andExpect(jsonPath("$.days[0].opened_by_me").value(true))
                .andExpect(jsonPath("$.days[0].opens").value("10:00"))
                .andExpect(jsonPath("$.days[0].closes").value("13:00"))
                .andExpect(jsonPath("$.days[0].agenda_released").value(false));
        mvc.perform(get("/attendances/intentions/professional/{id}", owner.id()))
                .andExpect(jsonPath("$.days[?(@.date=='" + day + "')].store_open").value(hasItem(true)))
                .andExpect(jsonPath("$.days[?(@.date=='" + day + "')].agenda_released").value(hasItem(false)));

        // agendar direto nao vale; solicitar vale, e o aceite vira o agendamento
        String when = BusinessClock.at(day, LocalTime.of(11, 0)).toString();
        String intention = "{\"professionalId\":\"" + owner.id() + "\",\"serviceItemIds\":[\"" + itemId + "\"],\"desiredTime\":\"" + when + "\"}";
        mvc.perform(post("/attendances/intentions").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken).content(intention))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INTENTION_OUTSIDE_PROFESSIONAL_HOURS"));
        String created = mvc.perform(post("/attendances/cut-requests").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"professionalId\":\"" + owner.id() + "\",\"establishmentId\":\"" + establishmentId
                                + "\",\"serviceItemIds\":[\"" + itemId + "\"],\"desiredTime\":\"" + when + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agenda_requested").value(true))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(patch("/attendances/cut-requests/{id}/accept", new JSONObject(created).getString("id"))
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // montou a semana: a agenda fica disponivel e o dia aberto continua com o horario dele
        mvc.perform(put("/professional/me/hours/weekly").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token).content(week())).andExpect(status().isOk());
        mvc.perform(get("/professional/me/agenda").param("from", day.toString()).param("to", day.toString())
                        .header("Authorization", token))
                .andExpect(jsonPath("$.days[0].opens").value("10:00"))
                .andExpect(jsonPath("$.days[0].agenda_released").value(true));
        String next = BusinessClock.at(day.plusDays(1), LocalTime.of(11, 0)).toString();
        mvc.perform(post("/attendances/intentions").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", clientToken)
                        .content("{\"professionalId\":\"" + owner.id() + "\",\"serviceItemIds\":[\"" + itemId + "\"],\"desiredTime\":\"" + next + "\"}"))
                .andExpect(status().isCreated());
    }
}
