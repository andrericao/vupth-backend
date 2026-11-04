package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * DEC-185 (alinhado): o PRIMEIRO agendado do dia do profissional so pode ser marcado atrasado depois de horario agendado
 * + 10 min (409 LATE_TOO_EARLY com available_at antes disso); os demais, e quem entra na fila sem horario agendado (fila
 * vazia em qualquer hora do dia), seguem a regra de sempre (a marcacao e livre).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FirstOfDayLateIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    String profToken, professionalId, establishmentId;
    List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var prof = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = prof.token();
        this.professionalId = prof.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Primeiro");
        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, profToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));
        TestUserUtils.startShift(professionalRepository, establishmentId);
    }

    private String walkIn(int minutesFromNow) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(client.id()),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(minutesFromNow), 30,
                OffsetDateTime.now().plusMinutes(minutesFromNow));
        MvcResult result = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn();
        return new JSONObject(result.getResponse().getContentAsString()).getString("id");
    }

    /** O agendamento promovido: o mesmo SCHEDULED, com o horario agendado gravado. */
    private void agendadoPara(String id, OffsetDateTime desired) {
        Attendance a = attendanceRepository.findById(UUID.fromString(id)).orElseThrow();
        a.setDesiredTime(desired);
        attendanceRepository.save(a);
    }

    @Test
    @DisplayName("primeiro agendado: 409 LATE_TOO_EARLY ate horario+10; o 2o agendado e o walk-in (fila vazia) seguem livres; depois de +10, marca")
    void firstOfDayLock() throws Exception {
        String first = walkIn(10);
        String second = walkIn(40);
        String walkIn = walkIn(70);
        OffsetDateTime nineish = OffsetDateTime.now().plusMinutes(5);
        agendadoPara(first, nineish);
        agendadoPara(second, nineish.plusMinutes(30));

        mvc.perform(patch("/attendances/{id}/late", first).header("Authorization", profToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LATE_TOO_EARLY"))
                .andExpect(jsonPath("$.available_at").exists());
        assertThat(attendanceRepository.findById(UUID.fromString(first)).orElseThrow().getStatus())
                .isEqualTo(AttendanceStatus.SCHEDULED);

        // o 2o agendado do dia e quem entrou sem horario agendado: regra de sempre (marcacao livre)
        mvc.perform(patch("/attendances/{id}/late", second).header("Authorization", profToken))
                .andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/late", walkIn).header("Authorization", profToken))
                .andExpect(status().isOk());

        // passou de horario + 10: marca
        agendadoPara(first, OffsetDateTime.now().minusMinutes(11));
        mvc.perform(patch("/attendances/{id}/late", first).header("Authorization", profToken))
                .andExpect(status().isOk());
        assertThat(attendanceRepository.findById(UUID.fromString(first)).orElseThrow().getStatus())
                .isEqualTo(AttendanceStatus.LATE);
    }
}
