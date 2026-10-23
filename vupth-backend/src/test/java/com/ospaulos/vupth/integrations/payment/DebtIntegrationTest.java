package com.ospaulos.vupth.integrations.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.debt.repository.ProfessionalDebtRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.payment.PaymentMethod;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * divida-por-fora-e-abatimento (DEC-109/115): o Recebi por fora (cartao, dinheiro, nota) deixa o profissional devendo o preco menos o
 * que ele recebe; Pix nao gera; sem a flag do Painel a divida e so teste (ninguem deve); ligada, vira real, e o Painel quita.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DebtIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired PaymentService paymentService;
    @Autowired ProfessionalDebtRepository debtRepository;

    private final Faker faker = new Faker();

    private String ownerToken;
    private String professionalId;
    private String establishmentId;
    private UUID service;

    private void shop() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        ownerToken = owner.token();
        establishmentId = estab.id();
        professionalId = owner.id();
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Dono Profissional");
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
        // servico com a divisao do preco (o profissional recebe R$ 30,00): so assim o corte tem a parte do app que vira divida
        BigDecimal pro = BigDecimal.valueOf(30);
        BigDecimal price = com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit.clientPriceFor(pro);
        String created = mvc.perform(post("/establishment/" + establishmentId + "/service-items")
                        .contentType(MediaType.APPLICATION_JSON).header("Authorization", ownerToken)
                        .content(mapper.writeValueAsString(new com.ospaulos.vupth.api.serviceItem.dto.request.ServiceItemRequest(
                                UUID.fromString(establishmentId), ServiceItemType.CORTE_MAQUINA, "Corte", price, 30, price,
                                true, pro, pro))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        service = UUID.fromString(mapper.readTree(created).get("id").asText());
    }

    private Attendance finishedAndReceived(PaymentMethod method) throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String body = "{\"professionalId\":\"" + professionalId + "\",\"establishmentId\":\"" + establishmentId
                + "\",\"clientId\":\"" + client.id() + "\",\"serviceItemIds\":[\"" + service + "\"],\"scheduledTime\":\""
                + OffsetDateTime.now() + "\"}";
        String created = mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Attendance a = attendanceRepository.findById(UUID.fromString(mapper.readTree(created).get("id").asText())).orElseThrow();
        a.setStatus(AttendanceStatus.FINISHED);
        a.setEndTime(OffsetDateTime.now());
        attendanceRepository.save(a);
        paymentService.confirm(a.getId(), UUID.fromString(professionalId), method);
        return attendanceRepository.findById(a.getId()).orElseThrow();
    }

    private void flag(String adminToken, boolean real) throws Exception {
        mvc.perform(put("/admin/settings/debt").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"real\":" + real + "}").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.real").value(real));
    }

    @Test
    @DisplayName("por fora grava a divida inteira (preco - o que recebe): simulada ate o Painel ligar; Pix nao gera; ligada vira real; o Painel quita")
    void debtLifecycle() throws Exception {
        shop();
        var plain = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        mvc.perform(get("/professional/me/debt").header("Authorization", plain.token())).andExpect(status().isForbidden());
        mvc.perform(put("/admin/settings/debt").contentType(MediaType.APPLICATION_JSON).content("{\"real\":true}")
                .header("Authorization", plain.token())).andExpect(status().isForbidden());

        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        BaseUser base = userRepository.findById(UUID.fromString(admin.id())).orElseThrow();
        base.addRole(RoleType.ADMIN);
        userRepository.save(base);
        try {
            flag(admin.token(), false);

            Attendance outside = finishedAndReceived(PaymentMethod.CARTAO);
            BigDecimal debt = outside.getTotalValue().subtract(outside.getProReceives());
            assertThat(debt.signum()).isPositive();
            mvc.perform(get("/professional/me/debt").header("Authorization", ownerToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.real").value(false))
                    .andExpect(jsonPath("$.open_amount").value(0.0))
                    .andExpect(jsonPath("$.simulated_amount").value(debt.doubleValue()))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].simulated").value(true));

            Attendance pix = finishedAndReceived(PaymentMethod.PIX);
            assertThat(debtRepository.existsByAttendanceId(pix.getId())).as("Pix pessoal nao gera divida").isFalse();

            flag(admin.token(), true);
            Attendance real = finishedAndReceived(PaymentMethod.DINHEIRO);
            BigDecimal realDebt = real.getTotalValue().subtract(real.getProReceives());
            mvc.perform(get("/professional/me/debt").header("Authorization", ownerToken))
                    .andExpect(jsonPath("$.real").value(true))
                    .andExpect(jsonPath("$.open_amount").value(realDebt.doubleValue()))
                    .andExpect(jsonPath("$.simulated_amount").value(debt.doubleValue()))
                    .andExpect(jsonPath("$.limit").value(25.0))
                    .andExpect(jsonPath("$.items.length()").value(2));
            mvc.perform(get("/admin/debts").header("Authorization", admin.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.professional_id=='" + professionalId + "' && @.simulated==false)]").isNotEmpty());

            mvc.perform(post("/admin/debts/{id}/settle", professionalId).header("Authorization", admin.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.settled_amount").value(realDebt.doubleValue()));
            mvc.perform(get("/professional/me/debt").header("Authorization", ownerToken))
                    .andExpect(jsonPath("$.open_amount").value(0.0));
        } finally {
            flag(admin.token(), false);
        }
    }
}
