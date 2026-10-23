package com.ospaulos.vupth.integrations.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.model.BenefitBase;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.CampaignVisibility;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.service.CouponCreditService;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.referral.service.AdminReferralService;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * cupom-ativado-em-configuracoes (DEC-110/112/115): o cupom ativado e usado no corte; fora da lista de ativos e 422
 * COUPON_NOT_ACTIVE; o cliente paga o preco menos o desconto; cancelar devolve o cupom; ao concluir, o desconto vira credito do
 * profissional, que ele consulta e o painel de pagamentos soma.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CouponIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired CampaignRepository campaignRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired CouponCreditService couponCreditService;
    @Autowired AdminReferralService adminReferralService;

    private final Faker faker = new Faker();

    private record Shop(String ownerToken, String professionalId, String establishmentId, UUID service) {}

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
        return new Shop(owner.token(), owner.id(), estab.id(), UUID.fromString(s.id()));
    }

    private Campaign coupon(BigDecimal amount, BigDecimal percent, BenefitBase base) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setCode("C" + faker.number().digits(8));
        c.setName("Cupom " + c.getCode());
        c.setType(CampaignType.DISCOUNT);
        c.setVisibility(CampaignVisibility.PRIVATE);
        c.setValueAmount(amount);
        c.setValuePercent(percent);
        c.setBenefitBase(base);
        c.setStartsAt(OffsetDateTime.now().minusDays(1));
        c.setEndsAt(OffsetDateTime.now().plusDays(10));
        return campaignRepository.save(c);
    }

    private void activate(String token, Campaign c) throws Exception {
        mvc.perform(post("/campaigns/redeem").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + c.getCode() + "\"}")).andExpect(status().isCreated());
    }

    private ResultActions enter(Shop shop, TestUserUtils.UserAuthAndToken client, UUID coupon) throws Exception {
        String body = "{\"professionalId\":\"" + shop.professionalId() + "\",\"establishmentId\":\"" + shop.establishmentId()
                + "\",\"clientId\":\"" + client.id() + "\",\"serviceItemIds\":[\"" + shop.service() + "\"],\"scheduledTime\":\""
                + OffsetDateTime.now() + "\"" + (coupon == null ? "" : ",\"coupon_campaign_id\":\"" + coupon + "\"") + "}";
        return mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", client.token()).content(body));
    }

    private JsonNode json(ResultActions r) throws Exception {
        return mapper.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("cupom fora da lista de ativos: 422 COUPON_NOT_ACTIVE e nada fica criado; ativado, o corte sai com desconto e o cliente paga a diferenca")
    void notActiveThenDiscount() throws Exception {
        Shop shop = shop();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        Campaign c = coupon(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);

        enter(shop, client, c.getId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_ACTIVE"));
        assertThat(attendanceRepository.existsByClient_IdAndStatusIn(UUID.fromString(client.id()),
                java.util.List.of(AttendanceStatus.SCHEDULED))).as("o corte recusado nao ficou na fila").isFalse();
        enter(shop, client, UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_ACTIVE"));

        activate(client.token(), c);
        mvc.perform(get("/campaigns/me").header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].benefit_base").value("WITH_FEE"))
                .andExpect(jsonPath("$[0].used").value(false));

        JsonNode created = json(enter(shop, client, c.getId()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.discount").value(5.0)));
        UUID id = UUID.fromString(created.get("id").asText());
        Attendance a = attendanceRepository.findById(id).orElseThrow();
        assertThat(a.getCouponCampaignId()).isEqualTo(c.getId());
        assertThat(a.getDiscount()).isEqualByComparingTo("5.00");
        assertThat(created.get("totalValue").decimalValue())
                .as("o totalValue da resposta ja vem com o desconto").isEqualByComparingTo(a.getTotalValue().subtract(new BigDecimal("5.00")));

        mvc.perform(get("/attendances/{id}/payment", id).header("Authorization", client.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(a.getTotalValue().subtract(new BigDecimal("5.00")).doubleValue()));
        mvc.perform(get("/campaigns/me").header("Authorization", client.token()))
                .andExpect(jsonPath("$[0].status").value("USED"))
                .andExpect(jsonPath("$[0].used").value(true));

        // cancelado, o cupom volta; cliente de outro corte nao usa o cupom de outro
        a.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(a);
        mvc.perform(get("/campaigns/me").header("Authorization", client.token()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
        var stranger = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        enter(shop, stranger, c.getId()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("cupom vencido ou usado dentro do mesmo corte: 422; porcentagem sobre o preco e limite no valor do corte")
    void expiredUsedAndCap() throws Exception {
        Shop shop = shop();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        Campaign big = coupon(new BigDecimal("500.00"), null, BenefitBase.WITH_FEE);
        activate(client.token(), big);
        JsonNode created = json(enter(shop, client, big.getId()).andExpect(status().isCreated()));
        Attendance a = attendanceRepository.findById(UUID.fromString(created.get("id").asText())).orElseThrow();
        assertThat(a.getDiscount()).as("o desconto nunca passa do valor do corte").isEqualByComparingTo(a.getTotalValue());
        a.setStatus(AttendanceStatus.CANCELLED);
        attendanceRepository.save(a);

        Campaign ended = coupon(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        activate(client.token(), ended);
        ended.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        campaignRepository.save(ended);
        enter(shop, client, ended.getId()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_NOT_ACTIVE"));

        Campaign tenPercent = coupon(null, new BigDecimal("10.00"), BenefitBase.WITHOUT_FEE);
        activate(client.token(), tenPercent);
        JsonNode second = json(enter(shop, client, tenPercent.getId()).andExpect(status().isCreated()));
        Attendance b = attendanceRepository.findById(UUID.fromString(second.get("id").asText())).orElseThrow();
        BigDecimal base = b.getProReceives() != null ? b.getProReceives() : b.getTotalValue();
        assertThat(b.getDiscount()).isEqualByComparingTo(base.multiply(new BigDecimal("0.10"))
                .setScale(2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("ao concluir, o desconto vira credito do profissional (uma vez); ele consulta; o painel soma; pago zera o em aberto")
    void creditToProfessional() throws Exception {
        Shop shop = shop();
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        Campaign c = coupon(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        c.setPayoutPeriod(com.ospaulos.vupth.domain.campaign.model.PayoutPeriod.WEEKLY);
        c.setPayoutDay(3);
        campaignRepository.save(c);
        activate(client.token(), c);
        JsonNode created = json(enter(shop, client, c.getId()).andExpect(status().isCreated()));
        UUID id = UUID.fromString(created.get("id").asText());

        couponCreditService.credit(id);
        assertThat(couponCreditService.creditsOf(UUID.fromString(shop.professionalId())).items())
                .as("sem concluir nao ha credito").isEmpty();

        Attendance a = attendanceRepository.findById(id).orElseThrow();
        a.setStatus(AttendanceStatus.FINISHED);
        a.setEndTime(OffsetDateTime.now());
        attendanceRepository.save(a);
        couponCreditService.credit(id);
        couponCreditService.credit(id);

        mvc.perform(get("/professional/me/credits").header("Authorization", shop.ownerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open_amount").value(5.0))
                .andExpect(jsonPath("$.next_payout").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].attendance_id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].paid_at").doesNotExist());
        mvc.perform(get("/professional/me/credits").header("Authorization", client.token()))
                .andExpect(status().isForbidden());

        assertThat(adminReferralService.payouts())
                .anyMatch(p -> p.beneficiaryId().equals(UUID.fromString(shop.professionalId()))
                        && p.amount().compareTo(new BigDecimal("5.00")) == 0);
        adminReferralService.markPaid(UUID.randomUUID(), c.getId(), UUID.fromString(shop.professionalId()));
        mvc.perform(get("/professional/me/credits").header("Authorization", shop.ownerToken()))
                .andExpect(jsonPath("$.open_amount").value(0.0))
                .andExpect(jsonPath("$.items[0].paid_at").isNotEmpty());
    }
}
