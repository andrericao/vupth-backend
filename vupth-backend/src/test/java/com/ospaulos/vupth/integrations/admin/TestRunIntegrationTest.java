package com.ospaulos.vupth.integrations.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.payment.PaymentStatus;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.payment.service.PaymentService;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * modo-piloto-sem-cobranca (DEC-106): o ADMIN declara um teste (rodada com nome) numa loja; os atendimentos criados
 * enquanto ele esta ativo levam test e o id da rodada; o Recebi quita como sempre, mas fora do livro-caixa; o painel
 * mostra tudo a parte como simbolico; depois de encerrado, o atendimento novo nasce sem marca.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TestRunIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired PaymentService paymentService;
    @Autowired AppLedgerRepository ledgerRepository;

    private final Faker faker = new Faker();

    private record Shop(String ownerToken, String professionalId, String establishmentId, List<UUID> services) {}

    private Shop shop() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        TestUserUtils.openEstablishment(establishmentRepository, estab.id());
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Dono Profissional");
        TestUserUtils.startShift(professionalRepository, estab.id());
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(owner.id())));
        var s = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        return new Shop(owner.token(), owner.id(), estab.id(), List.of(UUID.fromString(s.id())));
    }

    private Attendance enter(Shop shop) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(UUID.fromString(shop.professionalId()),
                UUID.fromString(shop.establishmentId()), UUID.fromString(client.id()), shop.services(),
                AttendanceStatus.SCHEDULED, OffsetDateTime.now(), 30, OffsetDateTime.now(), null);
        String body = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return attendanceRepository.findById(UUID.fromString(mapper.readTree(body).get("id").asText())).orElseThrow();
    }

    private void finishAndReceive(Attendance a, String professionalId) {
        a.setStatus(AttendanceStatus.FINISHED);
        a.setEndTime(OffsetDateTime.now());
        attendanceRepository.save(a);
        paymentService.confirm(a.getId(), UUID.fromString(professionalId));
    }

    @Test
    @DisplayName("rodada de teste: nome, so uma ativa, atendimento marcado e fora do livro-caixa, painel a parte, busca pelo nome, encerrar; quem nao e admin leva 403")
    void testRunLifecycle() throws Exception {
        Shop shop = shop();
        Shop other = shop();
        String name = "Piloto Barbearia " + UUID.randomUUID();

        var plain = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/admin/establishments/{id}/test-runs", shop.establishmentId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}")
                        .header("Authorization", plain.token()))
                .andExpect(status().isForbidden());

        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        mvc.perform(post("/admin/establishments/{id}/test-runs", shop.establishmentId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}")
                        .header("Authorization", admin.token()))
                .andExpect(status().isUnprocessableEntity());

        String started = mvc.perform(post("/admin/establishments/{id}/test-runs", shop.establishmentId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}")
                        .header("Authorization", admin.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(mapper.readTree(started).get("id").asText());

        mvc.perform(post("/admin/establishments/{id}/test-runs", shop.establishmentId())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"outro\"}")
                        .header("Authorization", admin.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_RUN_ALREADY_ACTIVE"));

        Attendance inTest = enter(shop);
        Attendance inOther = enter(other);
        assertThat(inTest.isTest()).isTrue();
        assertThat(inTest.getTestRunId()).isEqualTo(runId);
        assertThat(inOther.isTest()).as("a outra loja nao esta em teste").isFalse();

        finishAndReceive(inTest, shop.professionalId());
        finishAndReceive(inOther, other.professionalId());
        assertThat(attendanceRepository.findById(inTest.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(ledgerRepository.existsByAttendanceId(inTest.getId())).as("teste fora do livro-caixa").isFalse();
        assertThat(ledgerRepository.existsByAttendanceId(inOther.getId())).as("loja normal no livro-caixa").isTrue();

        mvc.perform(get("/admin/establishments").param("size", "100").header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + shop.establishmentId() + "')].active_test_name").value(name));

        mvc.perform(get("/admin/test-runs").param("q", name.toUpperCase()).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].attendances").value(1))
                .andExpect(jsonPath("$.items[0].symbolic_amount").isNumber());
        mvc.perform(get("/admin/test-runs/{id}", runId).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendances[0].attendance_id").value(inTest.getId().toString()));
        JsonNode finance = mapper.readTree(mvc.perform(get("/admin/finance").header("Authorization", admin.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(finance.get("pilot").get("attendances").asLong()).isGreaterThanOrEqualTo(1);

        mvc.perform(post("/admin/test-runs/{id}/end", runId).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(post("/admin/test-runs/{id}/end", runId).header("Authorization", admin.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_RUN_ALREADY_ENDED"));
        mvc.perform(post("/admin/test-runs/{id}/end", UUID.randomUUID()).header("Authorization", admin.token()))
                .andExpect(status().isNotFound());

        Attendance after = enter(shop);
        assertThat(after.isTest()).as("depois de encerrado, o atendimento nasce sem marca").isFalse();
        assertThat(attendanceRepository.findById(inTest.getId()).orElseThrow().isTest())
                .as("a marca do que ja existe nao muda").isTrue();
    }
}
