package com.ospaulos.vupth.unit.referral;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.event.AttendanceFinishedEvent;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.model.*;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.referral.model.Referral;
import com.ospaulos.vupth.domain.referral.model.ReferralCredit;
import com.ospaulos.vupth.domain.referral.model.ReferralShare;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.referral.service.ReferralCreditService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Bonificacao por link, o credito (BAK-9, fatia 2): quem recebe, quanto, e quando nao ha credito. */
class ReferralCreditServiceTest {

    private final ReferralRepository referralRepository = mock(ReferralRepository.class);
    private final ReferralCreditRepository creditRepository = mock(ReferralCreditRepository.class);
    private final AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final EstablishmentRepository establishmentRepository = mock(EstablishmentRepository.class);
    private final ProfessionalRepository professionalRepository = mock(ProfessionalRepository.class);
    private final ReferralCreditService service = new ReferralCreditService(referralRepository, creditRepository,
            attendanceRepository, campaignRepository, establishmentRepository, professionalRepository);

    private final UUID clientId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final UUID attendedById = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    /** Uma terca as 15h em Sao Paulo (18h UTC). */
    private final OffsetDateTime tuesday = OffsetDateTime.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.next(DayOfWeek.TUESDAY)).withHour(18).withMinute(0);
    private Attendance attendance;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        Establishment shop = new Establishment();
        shop.setId(shopId);
        BaseUser proUser = new BaseUser();
        proUser.setId(attendedById);
        Professional pro = new Professional(proUser);
        pro.setEstablishment(shop);
        BaseUser clientUser = new BaseUser();
        clientUser.setId(clientId);

        attendance = new Attendance();
        attendance.setId(UUID.randomUUID());
        attendance.setClient(new Client(clientUser));
        attendance.setProfessional(pro);
        attendance.setEstablishment(shop);
        attendance.setStatus(AttendanceStatus.FINISHED);
        attendance.setEndTime(tuesday);
        when(attendanceRepository.findByIdEager(attendance.getId())).thenReturn(Optional.of(attendance));
        when(attendanceRepository.countByClient_IdAndStatus(clientId, AttendanceStatus.FINISHED)).thenReturn(1L);
        when(professionalRepository.findById(any())).thenReturn(Optional.empty());
        when(professionalRepository.findById(attendedById)).thenReturn(Optional.of(pro));
        when(establishmentRepository.findByIdAndOwnerId(any(), any())).thenReturn(Optional.empty());

        campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setType(CampaignType.REFERRAL_BONUS);
        campaign.setValueAmount(new BigDecimal("5.00"));
        campaign.setReferralProfessionalPercent(new BigDecimal("60"));
        campaign.setReferralOwnerPercent(new BigDecimal("40"));
        campaign.setReferralScope(ReferralScope.ANY_PROFESSIONAL);
        campaign.setStartsAt(OffsetDateTime.now().minusDays(30));
        campaign.setEndsAt(tuesday.plusDays(30));
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(creditRepository.sumByCampaign(any())).thenReturn(BigDecimal.ZERO);
    }

    /** O cliente chegou pelo link de {@code referrerId} na campanha do teste. */
    private void referredBy(UUID referrerId) {
        Referral referral = referredWithoutSlot(referrerId);
        referral.takeSlot(OffsetDateTime.now()); // o corte iniciou e levou a vaga (DEC-124)
    }

    private Referral referredWithoutSlot(UUID referrerId) {
        Referral referral = new Referral(clientId, referrerId, OffsetDateTime.now(), campaign.getId());
        when(referralRepository.findById(clientId)).thenReturn(Optional.of(referral));
        return referral;
    }

    @Test
    @DisplayName("DEC-124: o corte iniciou no prazo e ha vaga → o indicado leva a vaga")
    void startTakesSlot() {
        Referral referral = referredWithoutSlot(otherId);
        campaign.setMaxRedemptions(2);
        when(referralRepository.countByCampaignIdAndSlotTakenAtIsNotNull(campaign.getId())).thenReturn(1L);

        service.onStarted(new com.ospaulos.vupth.domain.attendance.event.AttendanceStartedEvent(attendance.getId()));

        assertThat(referral.getSlotTakenAt()).isNotNull();
        verify(referralRepository).save(referral);
    }

    @Test
    @DisplayName("DEC-124: sem vaga ou encerrada no instante do inicio → nao leva a vaga e nao paga no fim")
    void startWithoutSlotDoesNotTake() {
        Referral referral = referredWithoutSlot(otherId);
        campaign.setMaxRedemptions(2);
        when(referralRepository.countByCampaignIdAndSlotTakenAtIsNotNull(campaign.getId())).thenReturn(2L);
        service.onStarted(new com.ospaulos.vupth.domain.attendance.event.AttendanceStartedEvent(attendance.getId()));
        assertThat(referral.getSlotTakenAt()).as("esgotada").isNull();

        campaign.setMaxRedemptions(null);
        campaign.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        service.onStarted(new com.ospaulos.vupth.domain.attendance.event.AttendanceStartedEvent(attendance.getId()));
        assertThat(referral.getSlotTakenAt()).as("encerrada").isNull();

        service.onFinished(new AttendanceFinishedEvent(attendance.getId()));
        verify(creditRepository, never()).save(any());
        verify(referralRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEC-124: a vaga nao volta — recomecar o corte nao consome outra, e quem ja a tem nao a perde se a campanha acabar")
    void slotIsKept() {
        Referral referral = referredWithoutSlot(otherId);
        OffsetDateTime first = OffsetDateTime.now().minusHours(1);
        referral.takeSlot(first);
        campaign.setEndsAt(OffsetDateTime.now().minusMinutes(1));

        service.onStarted(new com.ospaulos.vupth.domain.attendance.event.AttendanceStartedEvent(attendance.getId()));
        assertThat(referral.getSlotTakenAt()).isEqualTo(first);

        service.onFinished(new AttendanceFinishedEvent(attendance.getId()));
        assertThat(saved(1)).hasSize(1); // paga, mesmo com a campanha encerrada depois do inicio
    }

    private List<ReferralCredit> saved(int times) {
        ArgumentCaptor<ReferralCredit> captor = ArgumentCaptor.forClass(ReferralCredit.class);
        verify(creditRepository, times(times)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("quem compartilhou atendeu o proprio indicado: leva o valor inteiro (DEC-123, quem trouxe o cliente ganha)")
    void referrerAttendedStillPays() {
        referredBy(attendedById);

        service.onFinished(new AttendanceFinishedEvent(attendance.getId()));

        ReferralCredit c = saved(1).get(0);
        assertThat(c.getBeneficiaryId()).isEqualTo(attendedById);
        assertThat(c.getShare()).isEqualTo(ReferralShare.REFERRER);
        assertThat(c.getAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("outro profissional atendeu: quem compartilhou leva o valor inteiro, travado ate o Andre liberar")
    void referrerOtherAttended() {
        referredBy(otherId);

        service.onFinished(new AttendanceFinishedEvent(attendance.getId()));

        ReferralCredit c = saved(1).get(0);
        assertThat(c.getBeneficiaryId()).isEqualTo(otherId);
        assertThat(c.getShare()).isEqualTo(ReferralShare.REFERRER);
        assertThat(c.getAmount()).isEqualByComparingTo("5.00");
        assertThat(c.getCampaignId()).isEqualTo(campaign.getId());
        assertThat(c.getClientId()).isEqualTo(clientId);
        assertThat(c.getAttendanceId()).isEqualTo(attendance.getId());
        assertThat(c.getReleasedAt()).as("o premio nasce travado").isNull();
    }

    @Test
    @DisplayName("indicado sem campanha de adesao nao paga; teto de gasto da campanha: nao passa dele")
    void noCampaignAndCap() {
        when(referralRepository.findById(clientId))
                .thenReturn(Optional.of(new Referral(clientId, otherId, OffsetDateTime.now())));
        service.credit(attendance.getId());
        verify(creditRepository, never()).save(any());

        referredBy(otherId);
        campaign.setMaxTotalCost(new BigDecimal("12.00"));
        when(creditRepository.sumByCampaign(campaign.getId())).thenReturn(new BigDecimal("8.00"));
        service.credit(attendance.getId());
        verify(creditRepository, never()).save(any());

        when(creditRepository.sumByCampaign(campaign.getId())).thenReturn(new BigDecimal("7.00"));
        service.credit(attendance.getId());
        assertThat(saved(1)).hasSize(1);
    }

    @Test
    @DisplayName("link do dono, outro profissional atendeu: 60% ao profissional e 40% ao dono (somam o valor)")
    void ownerSplit() {
        referredBy(otherId);
        when(establishmentRepository.findByIdAndOwnerId(shopId, otherId)).thenReturn(Optional.of(new Establishment()));
        campaign.setValueAmount(new BigDecimal("5.55"));

        service.credit(attendance.getId());

        List<ReferralCredit> credits = saved(2);
        assertThat(credits.get(0).getShare()).isEqualTo(ReferralShare.PROFESSIONAL);
        assertThat(credits.get(0).getBeneficiaryId()).isEqualTo(attendedById);
        assertThat(credits.get(0).getAmount()).isEqualByComparingTo("3.33");
        assertThat(credits.get(1).getShare()).isEqualTo(ReferralShare.OWNER);
        assertThat(credits.get(1).getBeneficiaryId()).isEqualTo(otherId);
        assertThat(credits.get(1).getAmount()).isEqualByComparingTo("2.22");
    }

    @Test
    @DisplayName("divisao com uma parte zero: so a outra parte vira credito")
    void zeroPartSkipped() {
        referredBy(otherId);
        when(establishmentRepository.findByIdAndOwnerId(shopId, otherId)).thenReturn(Optional.of(new Establishment()));
        campaign.setReferralProfessionalPercent(BigDecimal.ZERO);
        campaign.setReferralOwnerPercent(new BigDecimal("100"));

        service.credit(attendance.getId());

        assertThat(saved(1).get(0).getShare()).isEqualTo(ReferralShare.OWNER);
    }

    @Test
    @DisplayName("profissional de outra loja indicou (quem conta: qualquer): leva o valor inteiro")
    void otherShopReferrerAny() {
        referredBy(otherId);

        service.credit(attendance.getId());

        assertThat(saved(1).get(0).getBeneficiaryId()).isEqualTo(otherId);
    }

    @Test
    @DisplayName("quem conta = a loja de quem indicou: de outra loja nao conta; da mesma loja conta")
    void scopeEstablishment() {
        campaign.setReferralScope(ReferralScope.REFERRER_ESTABLISHMENT);
        referredBy(otherId);
        service.credit(attendance.getId());
        verify(creditRepository, never()).save(any());

        Establishment shop = new Establishment();
        shop.setId(shopId);
        BaseUser colleagueUser = new BaseUser();
        colleagueUser.setId(otherId);
        Professional colleague = new Professional(colleagueUser);
        colleague.setEstablishment(shop);
        when(professionalRepository.findById(otherId)).thenReturn(Optional.of(colleague));
        service.credit(attendance.getId());
        assertThat(saved(1).get(0).getShare()).isEqualTo(ReferralShare.REFERRER);
    }

    @Test
    @DisplayName("fora dos dias da campanha ou sem valor em reais: sem credito (a vaga e tomada no inicio do corte, DEC-124)")
    void campaignFilters() {
        referredBy(otherId);
        campaign.setWeekdays("MONDAY,WEDNESDAY");
        service.credit(attendance.getId());

        campaign.setWeekdays("TUESDAY");
        campaign.setValueAmount(null);
        service.credit(attendance.getId());

        // a vaga nao se confere no fim (DEC-124): foi contada quando o corte iniciou
        campaign.setValueAmount(new BigDecimal("5.00"));
        verify(creditRepository, never()).save(any());

        service.credit(attendance.getId());
        assertThat(saved(1)).hasSize(1);
    }

    @Test
    @DisplayName("sem credito: sem indicacao, credito ja dado, nao e o primeiro corte, sem campanha, sem cliente, nao terminou")
    void noCredit() {
        service.credit(attendance.getId());

        referredBy(otherId);
        when(creditRepository.existsByClientId(clientId)).thenReturn(true);
        service.credit(attendance.getId());

        when(creditRepository.existsByClientId(clientId)).thenReturn(false);
        when(attendanceRepository.countByClient_IdAndStatus(clientId, AttendanceStatus.FINISHED)).thenReturn(2L);
        service.credit(attendance.getId());

        when(attendanceRepository.countByClient_IdAndStatus(clientId, AttendanceStatus.FINISHED)).thenReturn(1L);
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.empty());
        service.credit(attendance.getId());

        attendance.setStatus(AttendanceStatus.IN_PROGRESS);
        service.credit(attendance.getId());

        attendance.setClient(null);
        service.credit(attendance.getId());

        UUID missing = UUID.randomUUID();
        when(attendanceRepository.findByIdEager(missing)).thenReturn(Optional.empty());
        service.credit(missing);

        verify(creditRepository, never()).save(any());
    }

    @Test
    @DisplayName("sem hora de fim, usa agora; uma falha so e logada, nao derruba o Finalizar")
    void endTimeFallbackAndFailureSwallowed() {
        referredBy(otherId);
        attendance.setEndTime(null);
        service.credit(attendance.getId());
        assertThat(saved(1)).hasSize(1);

        when(attendanceRepository.findByIdEager(attendance.getId())).thenThrow(new IllegalStateException("banco fora"));
        service.onFinished(new AttendanceFinishedEvent(attendance.getId()));
    }
}
