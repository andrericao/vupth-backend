package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * sem-multa-no-primeiro-atendimento (DEC-104): o cliente sem nenhum atendimento FINALIZADO e isento; o
 * {@code wait-time} diz {@code penaltyExempt=true} e {@code exitPenaltyValue=0.00}. Cancelado ou removido nao conta
 * como concluido; depois de um FINALIZADO a regra normal volta ({@code penaltyExempt=false}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "vupth.penalty.first-attendance-exempt=true")
class FirstAttendanceExemptionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ClientPenaltyEventRepository penaltyEventRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();
    private String ownerProfToken, professionalId, establishmentId;
    private List<UUID> serviceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken);
        this.establishmentId = estab.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(s1.id()));
    }

    @AfterEach
    void clear() {
        penaltyEventRepository.deleteAll();
        attendanceRepository.deleteAll();
    }

    private String enter(TestUserUtils.UserAuthAndToken client) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(client.id()),
                serviceItemIds, AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()).content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private JSONObject waitTime(TestUserUtils.UserAuthAndToken client, String attendanceId) throws Exception {
        MvcResult r = mvc.perform(get("/client-queue/{attendanceId}/wait-time", attendanceId)
                        .param("clientId", client.id()).header("Authorization", client.token()))
                .andExpect(status().isOk()).andReturn();
        return new JSONObject(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("cliente novo: penaltyExempt=true e sair custa 0.00; atendimento removido nao tira a isencao; um FINALIZADO a tira")
    void exemptUntilFirstFinished() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String first = enter(client);

        JSONObject wait = waitTime(client, first);
        assertThat(wait.getBoolean("penaltyExempt")).isTrue();
        assertThat(new BigDecimal(wait.get("exitPenaltyValue").toString())).isEqualByComparingTo("0.00");

        // removido/cancelado nao conta como concluido: continua isento
        var canceled = attendanceRepository.findById(UUID.fromString(first)).orElseThrow();
        canceled.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(canceled);
        String second = enter(client);
        assertThat(waitTime(client, second).getBoolean("penaltyExempt")).isTrue();

        // com um atendimento FINALIZADO a isencao acaba
        var done = attendanceRepository.findById(UUID.fromString(second)).orElseThrow();
        done.setStatus(AttendanceStatus.FINISHED);
        done.setPaymentStatus(com.ospaulos.vupth.domain.payment.PaymentStatus.PAID); // pendente de pagamento barra a fila
        attendanceRepository.save(done);
        String third = enter(client);
        assertThat(waitTime(client, third).getBoolean("penaltyExempt")).isFalse();
    }
}
