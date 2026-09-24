package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integracao — <b>{@code REMOVED} (a loja tirou) x {@code CANCELLED} (o cliente desistiu)</b>, decisao
 * do Andre de 18/09.
 *
 * <p>Era um status so, e o app nao tinha como contar a historia certa: "voce saiu da fila" e "voce foi
 * retirado" chegavam iguais. A separacao e por <b>quem praticou o ato</b>, nao pelo motivo — o motivo
 * (no-show, por exemplo) continua auditado a parte, no {@code ClientPenaltyEvent}.</p>
 *
 * <p>Os dois desfechos sao terminais e tiram o cliente da fila do mesmo jeito; o que este teste fixa e
 * que o <b>rotulo</b> nao se confunde, inclusive na guarda de "ja saiu" — cancelar o que a loja ja
 * retirou e o mesmo erro de sempre.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RemovedVersusCancelledIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired AttendanceRepository attendanceRepository;

    private final Faker faker = new Faker();

    private String profToken, profId, establishmentId;
    private UUID serviceItemId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.profToken = owner.token();
        this.profId = owner.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, profToken).id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, profToken, establishmentId, "Prof");
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(profId)));
        this.serviceItemId = UUID.fromString(TestUserUtils.createServiceItem(mvc, mapper, establishmentId,
                profToken, ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30)).id());

        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.startShift(professionalRepository, establishmentId);
    }

    /** Um cliente novo na fila — devolve o id do atendimento. */
    private String entrarNaFila() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(profId), UUID.fromString(establishmentId), UUID.fromString(client.id()),
                List.of(serviceItemId), AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        String body = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(body).get("id").asText();
        clientTokenDoUltimo = client.token();
        return id;
    }

    private String clientTokenDoUltimo;

    private AttendanceStatus statusDe(String attendanceId) {
        return attendanceRepository.findById(UUID.fromString(attendanceId)).orElseThrow().getStatus();
    }

    @Test
    @Order(10)
    @DisplayName("a LOJA tira o cliente da fila -> REMOVED")
    void professionalRemoves_isRemoved() throws Exception {
        String id = entrarNaFila();

        mvc.perform(delete("/professional-queue/{id}/remove", id)
                        .header("Authorization", profToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"))
                .andDo(print());

        assertThat(statusDe(id)).isEqualTo(AttendanceStatus.REMOVED);
    }

    @Test
    @Order(20)
    @DisplayName("o CLIENTE desiste -> CANCELLED (o rotulo nao se confunde)")
    void clientLeaves_isCancelled() throws Exception {
        String id = entrarNaFila();

        mvc.perform(delete("/attendances/{id}/leave-queue", id)
                        .header("Authorization", clientTokenDoUltimo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andDo(print());

        assertThat(statusDe(id)).isEqualTo(AttendanceStatus.CANCELLED);
    }

    @Test
    @Order(30)
    @DisplayName("cancelar o que a loja JA retirou e o mesmo erro de sempre")
    void cancellingWhatWasRemoved_failsLikeBefore() throws Exception {
        String id = entrarNaFila();
        String clientToken = clientTokenDoUltimo;

        mvc.perform(delete("/professional-queue/{id}/remove", id)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        // A guarda de "ja saiu da fila" cobre os DOIS desfechos — sem isto, o REMOVED seria
        // sobrescrito em silencio por um CANCELLED e a loja perderia o registro de quem tirou.
        mvc.perform(delete("/attendances/{id}/leave-queue", id)
                        .header("Authorization", clientToken))
                .andExpect(status().is4xxClientError())
                .andDo(print());

        assertThat(statusDe(id)).as("o rotulo de quem tirou sobrevive").isEqualTo(AttendanceStatus.REMOVED);
    }

    @Test
    @Order(40)
    @DisplayName("os dois saem da fila viva do mesmo jeito — o rotulo nao muda a fila")
    void bothLeaveTheLiveQueue() throws Exception {
        String removido = entrarNaFila();
        mvc.perform(delete("/professional-queue/{id}/remove", removido)
                        .header("Authorization", profToken))
                .andExpect(status().isOk());

        String desistente = entrarNaFila();
        String tokenDesistente = clientTokenDoUltimo;
        mvc.perform(delete("/attendances/{id}/leave-queue", desistente)
                        .header("Authorization", tokenDesistente))
                .andExpect(status().isOk());

        // As consultas da fila trabalham por lista branca de status (SCHEDULED/LATE/...), entao um
        // status terminal novo nao entra sozinho na fila de ninguem. E isto que prova.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professionals[0].waiting_list[?(@.id == '" + removido + "')]").isEmpty())
                .andExpect(jsonPath("$.professionals[0].waiting_list[?(@.id == '" + desistente + "')]").isEmpty())
                .andDo(print());
    }
}
