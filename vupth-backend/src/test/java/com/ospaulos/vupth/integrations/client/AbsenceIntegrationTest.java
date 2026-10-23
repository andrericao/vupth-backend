package com.ospaulos.vupth.integrations.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.service.ClientAbsenceService;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.payment.PaymentStatus;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * tres-faltas-pagar-adiantado (DEC-105/115): cada saida da fila conta (inclusive do cliente isento da multa); com tres, o agendamento
 * sai com a marca de pagamento adiantado, mas agendar e entrar na fila no dia seguem livres; concluir e pagar um corte zera.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AbsenceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired TransactionTemplate tx;
    @Autowired ClientAbsenceService absenceService;

    private final Faker faker = new Faker();

    private String professionalId;
    private String establishmentId;
    private UUID service;

    private void shop() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        establishmentId = estab.id();
        professionalId = owner.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), establishmentId, "Dono Profissional");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        TestUserUtils.setStatusAndHours(tx, establishmentRepository, establishmentId, OpenStatus.OPEN,
                LocalTime.of(0, 0), LocalTime.of(23, 59));
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
        service = UUID.fromString(TestUserUtils.createServiceItem(mvc, mapper, establishmentId, owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)).id());
    }

    private UUID enter(TestUserUtils.UserAuthAndToken client) throws Exception {
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"clientId\":\"" + client.id() + "\",\"serviceItemIds\":[\"" + service + "\"],\"scheduledTime\":\""
                + OffsetDateTime.now() + "\"}";
        String created = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(created).get("id").asText());
    }

    private ResultActions schedule(TestUserUtils.UserAuthAndToken client) throws Exception {
        String body = "{\"professionalId\":\"" + professionalId + "\",\"serviceItemIds\":[\"" + service
                + "\"],\"desiredTime\":\"" + OffsetDateTime.now().plusDays(1) + "\"}";
        return mvc.perform(post("/attendances/intentions").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", client.token()).content(body));
    }

    private void assertAbsences(TestUserUtils.UserAuthAndToken client, int count, boolean prepay) throws Exception {
        mvc.perform(get("/clients/me/absences").header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absence_count").value(count))
                .andExpect(jsonPath("$.absence_limit").value(3))
                .andExpect(jsonPath("$.prepay_required").value(prepay));
    }

    @Test
    @DisplayName("tres saidas da fila ligam o pagamento adiantado no agendamento; agendar e entrar no dia seguem livres; pagar um corte zera")
    void threeAbsences() throws Exception {
        shop();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        assertAbsences(client, 0, false);
        schedule(client).andExpect(status().isCreated()).andExpect(jsonPath("$.prepay_required").value(false));
        cancelIntentions(client);

        UUID first = enter(client);
        mvc.perform(put("/attendances/{id}/cancel", first).header("Authorization", client.token())).andExpect(status().isOk());
        assertAbsences(client, 1, false);
        UUID second = enter(client);
        mvc.perform(delete("/attendances/{id}/leave-queue", second).header("Authorization", client.token()))
                .andExpect(status().isOk());
        assertAbsences(client, 2, false);
        UUID third = enter(client);
        mvc.perform(put("/attendances/{id}/cancel", third).header("Authorization", client.token())).andExpect(status().isOk());
        assertAbsences(client, 3, true);

        schedule(client).andExpect(status().isCreated()).andExpect(jsonPath("$.prepay_required").value(true));
        cancelIntentions(client);
        UUID stillFree = enter(client);
        assertThat(attendanceRepository.findById(stillFree).orElseThrow().isPrepayRequired())
                .as("entrar na fila no dia nao e barrado nem marcado").isFalse();

        // concluir e pagar um corte depois da ultima ocorrencia zera o contador
        Attendance a = attendanceRepository.findById(stillFree).orElseThrow();
        a.setStatus(AttendanceStatus.FINISHED);
        a.setEndTime(OffsetDateTime.now().plusSeconds(5));
        a.setPaymentStatus(PaymentStatus.PAID);
        attendanceRepository.save(a);
        assertThat(absenceService.statusOf(UUID.fromString(client.id())).absenceCount()).isZero();
    }

    private void cancelIntentions(TestUserUtils.UserAuthAndToken client) {
        attendanceRepository.findAll().stream()
                .filter(a -> a.getClient() != null && a.getClient().getId().toString().equals(client.id()))
                .filter(a -> a.getStatus() == AttendanceStatus.INTENTION)
                .forEach(a -> { a.setStatus(AttendanceStatus.CANCELLED); attendanceRepository.save(a); });
    }
}
