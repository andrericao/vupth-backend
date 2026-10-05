package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — adicionar-servico-na-fila (Client C8): {@code PATCH /attendances/{id}/service-items}.
 *
 * <p>So o proprio cliente, so em SCHEDULED. O tempo a mais so cabe se ninguem estiver depois na fila e
 * nenhuma intencao comecar antes do novo fim (regra do Andre, 15/09): senao 409
 * {@code ATTENDANCE_NO_ROOM_FOR_SERVICE}. Cada servico dura 30 min e as refeicoes saem no setup.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AddServiceItemsIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired UserRepository userRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    String ownerProfToken, professionalId, establishmentId;
    String corteId, barbaId, sobrancelhaId;
    BigDecimal cortePrice, barbaPrice;

    /** Cliente em fila: id do attendance + token. */
    private record Q(String id, String token) {}

    Q first, second;

    @BeforeAll
    void setup() throws Exception {
        var ownerProf = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerProfToken = ownerProf.token();
        this.professionalId = ownerProf.id();
        this.establishmentId = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerProfToken).id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerProfToken, establishmentId, "Owner Professional");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));

        var corte = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        var barba = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.BARBA, "Barba", BigDecimal.valueOf(20));
        this.corteId = corte.id();
        this.cortePrice = corte.price();
        this.barbaId = barba.id();
        this.barbaPrice = barba.price();
        this.sobrancelhaId = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerProfToken,
                ServiceItemType.SOBRANCELHA, "Sobrancelha", BigDecimal.valueOf(15)).id();
    }

    private Q createWalkin() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId), UUID.fromString(establishmentId), UUID.fromString(client.id()),
                List.of(UUID.fromString(corteId)), AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        MvcResult r = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as("walk-in criado").isEqualTo(201);
        return new Q(new JSONObject(r.getResponse().getContentAsString()).getString("id"), client.token());
    }

    private ResultActions add(Q q, String... itemIds) throws Exception {
        return add(q.id(), q.token(), itemIds);
    }

    private ResultActions add(String attendanceId, String token, String... itemIds) throws Exception {
        String ids = String.join("\",\"", itemIds);
        String body = itemIds.length == 0 ? "{\"serviceItemIds\":[]}" : "{\"serviceItemIds\":[\"" + ids + "\"]}";
        return mvc.perform(patch("/attendances/{id}/service-items", attendanceId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token)
                .content(body));
    }

    @Test
    @Order(10)
    @DisplayName("unico da fila adiciona a barba -> 200, +30 min e o preco da barba no total")
    void lastInQueue_addsService() throws Exception {
        this.first = createWalkin();

        add(first, barbaId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services", hasSize(2)))
                .andExpect(jsonPath("$.estimatedMinutes").value(60));

        Attendance saved = attendanceRepository.findByIdEager(UUID.fromString(first.id())).orElseThrow();
        assertThat(saved.getServiceItems()).hasSize(2);
        assertThat(saved.getEstimatedTotalMinutes()).isEqualTo(60);
        assertThat(saved.getTotalValue()).isEqualByComparingTo(cortePrice.add(barbaPrice));
        if (saved.getProReceives() != null) {
            assertThat(saved.getProReceives().add(saved.getIntermediaryFee()).add(saved.getPlatformFee()))
                    .as("a divisao continua somando o total").isEqualByComparingTo(saved.getTotalValue());
        }
    }

    @Test
    @Order(11)
    @DisplayName("item que ja esta no atendimento e ignorado (no-op, 200)")
    void alreadyPresent_isNoOp() throws Exception {
        add(first, barbaId, corteId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services", hasSize(2)))
                .andExpect(jsonPath("$.estimatedMinutes").value(60));
    }

    @Test
    @Order(20)
    @DisplayName("com alguem depois na fila -> 409 ATTENDANCE_NO_ROOM_FOR_SERVICE; o ultimo adiciona")
    void someoneBehind_conflict_lastAdds() throws Exception {
        this.second = createWalkin();

        add(first, sobrancelhaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NO_ROOM_FOR_SERVICE"));
        assertThat(attendanceRepository.findByIdEager(UUID.fromString(first.id())).orElseThrow()
                .getServiceItems()).hasSize(2);

        add(second, barbaId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimatedMinutes").value(60));
    }

    @Test
    @Order(30)
    @DisplayName("atendimento de outro cliente -> 403; item que nao existe -> 404; lista vazia -> 422")
    void ownership_unknownItem_emptyBody() throws Exception {
        add(first.id(), second.token(), barbaId).andExpect(status().isForbidden());
        add(second, UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERVICE_ITEM_NOT_FOUND"));
        add(second).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @Order(40)
    @DisplayName("intencao marcada antes do novo fim da fila -> 409 ATTENDANCE_NO_ROOM_FOR_SERVICE")
    void intentionAhead_conflict() throws Exception {
        // Uma intencao (agenda) para daqui a 5 min: o fim da fila com mais 30 min passaria por cima dela.
        Q third = createWalkin();
        Attendance intention = attendanceRepository.findById(UUID.fromString(third.id())).orElseThrow();
        intention.setStatus(AttendanceStatus.INTENTION);
        intention.setDesiredTime(OffsetDateTime.now().plusMinutes(5));
        attendanceRepository.save(intention);

        add(second, sobrancelhaId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_NO_ROOM_FOR_SERVICE"));

        intention.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(intention);
    }

    @Test
    @Order(50)
    @DisplayName("atendimento que ja comecou -> 400 ATTENDANCE_INVALID_STATUS")
    void notScheduled_badRequest() throws Exception {
        mvc.perform(patch("/attendances/{id}/start", first.id()).header("Authorization", ownerProfToken))
                .andExpect(status().isOk());

        add(first, sobrancelhaId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTENDANCE_INVALID_STATUS"));
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        serviceItemRepository.deleteAll();
        establishmentRepository.deleteAll();
        userRepository.deleteAll();
    }
}
