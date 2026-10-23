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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * modo-piloto-sem-cobranca (DEC-106) e teste-com-varias-lojas (DEC-108): o ADMIN declara UM teste com nome e as lojas; os
 * atendimentos criados enquanto a loja esta nele levam test e o id do teste; o Recebi quita como sempre, mas fora do
 * livro-caixa; o painel mostra tudo a parte como simbolico. Uma loja pode sair antes do fim (volta ao normal, as outras
 * seguem) e "finalizar" tira todas; depois, o atendimento novo nasce sem marca e entra no livro-caixa.
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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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
        return enterAs(shop, TestUserUtils.registerAndLoginUser(mvc, mapper, faker));
    }

    private Attendance enterAs(Shop shop, TestUserUtils.UserAuthAndToken client) throws Exception {
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

    private String body(String name, String... establishmentIds) {
        return "{\"name\":\"" + name + "\",\"establishment_ids\":[\""
                + String.join("\",\"", establishmentIds) + "\"]}";
    }

    @Test
    @DisplayName("teste com varias lojas: nome, uma loja so em um teste ativo, marca e livro-caixa, loja que sai volta ao normal, finalizar tira todas, busca pelo nome; quem nao e admin leva 403")
    void testRunLifecycle() throws Exception {
        Shop shop = shop();
        Shop second = shop();
        Shop other = shop();
        String name = "Piloto Barbearia " + UUID.randomUUID();

        var plain = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(post("/admin/test-runs").contentType(MediaType.APPLICATION_JSON)
                        .content(body("x", shop.establishmentId())).header("Authorization", plain.token()))
                .andExpect(status().isForbidden());

        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);

        mvc.perform(post("/admin/test-runs").contentType(MediaType.APPLICATION_JSON)
                        .content(body("", shop.establishmentId())).header("Authorization", admin.token()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/test-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sem lojas\",\"establishment_ids\":[]}").header("Authorization", admin.token()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/admin/test-runs").contentType(MediaType.APPLICATION_JSON)
                        .content(body("loja inexistente", UUID.randomUUID().toString())).header("Authorization", admin.token()))
                .andExpect(status().isNotFound());

        String started = mvc.perform(post("/admin/test-runs").contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, shop.establishmentId(), second.establishmentId()))
                        .header("Authorization", admin.token()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.establishments.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(mapper.readTree(started).get("id").asText());

        mvc.perform(post("/admin/test-runs").contentType(MediaType.APPLICATION_JSON)
                        .content(body("outro", other.establishmentId(), shop.establishmentId()))
                        .header("Authorization", admin.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_RUN_ALREADY_ACTIVE"));
        Attendance inOther0 = enter(other);
        assertThat(inOther0.isTest()).as("o teste recusado nao levou a outra loja junto").isFalse();

        Attendance inTest = enter(shop);
        Attendance inSecond = enter(second);
        Attendance inOther = enter(other);
        assertThat(inTest.isTest()).isTrue();
        assertThat(inTest.getTestRunId()).isEqualTo(runId);
        assertThat(inSecond.isTest()).isTrue();
        assertThat(inSecond.getTestRunId()).isEqualTo(runId);
        assertThat(inOther.isTest()).as("a loja fora do teste nao e marcada").isFalse();

        finishAndReceive(inTest, shop.professionalId());
        finishAndReceive(inOther, other.professionalId());
        assertThat(attendanceRepository.findById(inTest.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(ledgerRepository.existsByAttendanceId(inTest.getId())).as("teste fora do livro-caixa").isFalse();
        assertThat(ledgerRepository.existsByAttendanceId(inOther.getId())).as("loja normal no livro-caixa").isTrue();

        mvc.perform(get("/admin/establishments").param("size", "100").header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + shop.establishmentId() + "')].active_test_name").value(name));

        // uma loja sai do teste: volta ao normal; a outra segue; sair de novo ou de um teste que nao e dela, 409
        mvc.perform(delete("/admin/test-runs/{id}/establishments/{e}", runId, second.establishmentId())
                        .header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.establishments[?(@.establishment_id=='" + second.establishmentId() + "')].left_at")
                        .isNotEmpty());
        mvc.perform(delete("/admin/test-runs/{id}/establishments/{e}", runId, second.establishmentId())
                        .header("Authorization", admin.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_RUN_STORE_NOT_IN_TEST"));
        mvc.perform(delete("/admin/test-runs/{id}/establishments/{e}", UUID.randomUUID(), second.establishmentId())
                        .header("Authorization", admin.token()))
                .andExpect(status().isNotFound());
        Attendance backToNormal = enter(second);
        assertThat(backToNormal.isTest()).as("a loja que saiu volta a criar atendimento normal").isFalse();
        assertThat(attendanceRepository.findById(inSecond.getId()).orElseThrow().isTest())
                .as("o que ja existia segue marcado").isTrue();
        finishAndReceive(backToNormal, second.professionalId());
        assertThat(ledgerRepository.existsByAttendanceId(backToNormal.getId()))
                .as("loja que saiu volta ao livro-caixa").isTrue();
        Attendance stillInTest = enter(shop);
        assertThat(stillInTest.isTest()).as("a outra loja segue em teste").isTrue();

        // DEC-108 (cobra sim): a multa pendente do cliente entra no atendimento de teste e e cobrada como no real
        var fined = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        UUID pendingFine = UUID.randomUUID();
        jdbc.update("INSERT INTO client_penalty_event (id, client_id, type, eta_minutes, amount, created_at) "
                + "VALUES (?, ?, 'LATE_ARRIVAL', 0, 5.00, now())", pendingFine, UUID.fromString(fined.id()));
        Attendance finedInTest = enterAs(shop, fined);
        assertThat(finedInTest.isTest()).isTrue();
        assertThat(jdbc.queryForObject("SELECT charged_attendance_id FROM client_penalty_event WHERE id = ?",
                UUID.class, pendingFine)).as("a multa pendente foi cobrada no atendimento de teste")
                .isEqualTo(finedInTest.getId());

        mvc.perform(get("/admin/test-runs").param("q", name.toUpperCase()).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].attendances").value(4))
                .andExpect(jsonPath("$.items[0].symbolic_amount").isNumber());
        mvc.perform(get("/admin/test-runs/{id}", runId).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendances.length()").value(4));
        JsonNode finance = mapper.readTree(mvc.perform(get("/admin/finance").header("Authorization", admin.token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(finance.get("pilot").get("attendances").asLong()).isGreaterThanOrEqualTo(1);

        mvc.perform(post("/admin/test-runs/{id}/end", runId).header("Authorization", admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.establishments[?(@.left_at == null)]").isEmpty());
        mvc.perform(post("/admin/test-runs/{id}/end", runId).header("Authorization", admin.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEST_RUN_ALREADY_ENDED"));
        mvc.perform(post("/admin/test-runs/{id}/end", UUID.randomUUID()).header("Authorization", admin.token()))
                .andExpect(status().isNotFound());

        Attendance after = enter(shop);
        assertThat(after.isTest()).as("depois de finalizar, o atendimento nasce sem marca").isFalse();
        assertThat(attendanceRepository.findById(inTest.getId()).orElseThrow().isTest())
                .as("a marca do que ja existe nao muda").isTrue();
        finishAndReceive(after, shop.professionalId());
        assertThat(ledgerRepository.existsByAttendanceId(after.getId())).as("fora do teste entra no livro-caixa").isTrue();
    }
}
