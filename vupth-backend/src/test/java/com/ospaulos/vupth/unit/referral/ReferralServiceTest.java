package com.ospaulos.vupth.unit.referral;

import com.ospaulos.vupth.api.referral.dto.ReferralClaimResponse;
import com.ospaulos.vupth.api.referral.dto.ReferralMeResponse;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.model.Referral;
import com.ospaulos.vupth.domain.referral.model.ReferralCode;
import com.ospaulos.vupth.domain.referral.repository.ReferralCodeRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.referral.service.ReferralService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Indicacao (BAK-9): o codigo fixo de quem indica e a ligacao do cliente, cada recusa com o seu codigo. */
class ReferralServiceTest {

    private final ReferralCodeRepository codeRepository = mock(ReferralCodeRepository.class);
    private final ReferralRepository referralRepository = mock(ReferralRepository.class);
    private final ReferralCreditRepository creditRepository = mock(ReferralCreditRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ReferralService service = new ReferralService(codeRepository, referralRepository, creditRepository,
            userRepository, "https://vupth.pages.dev/i/");

    private final UUID referrerId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(referralRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private BaseUser user(UUID id, RoleType role) {
        BaseUser u = new BaseUser();
        u.setId(id);
        u.addRole(role);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ReferralException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("profissional sem codigo: nasce um de 6 letras legiveis, com o link e quantos chegaram")
    void meCreates() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findById(referrerId)).thenReturn(Optional.empty());
        when(referralRepository.countByReferrerId(referrerId)).thenReturn(3L);
        when(creditRepository.countClientsByBeneficiaryAndShareIn(eq(referrerId), anyCollection())).thenReturn(2L);
        when(creditRepository.sumUnpaidByBeneficiary(referrerId)).thenReturn(new BigDecimal("10.00"));

        ReferralMeResponse r = service.me(referrerId);

        assertThat(r.code()).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}");
        assertThat(r.link()).isEqualTo("https://vupth.pages.dev/i/" + r.code());
        assertThat(r.referredClients()).isEqualTo(3);
        assertThat(r.creditedClients()).isEqualTo(2);
        assertThat(r.toReceive()).isEqualByComparingTo("10.00");
        verify(codeRepository).save(any());
    }

    @Test
    @DisplayName("dono com codigo: devolve o mesmo, sem criar outro")
    void meKeeps() {
        user(referrerId, RoleType.OWNER);
        when(codeRepository.findById(referrerId))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));

        assertThat(service.me(referrerId).code()).isEqualTo("K7M2QX");
        verify(codeRepository, never()).save(any());
    }

    @Test
    @DisplayName("codigo repetido no sorteio: tenta de novo; cinco repetidos seguidos, erro")
    void meRetriesCollision() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findById(referrerId)).thenReturn(Optional.empty());
        when(codeRepository.existsByCode(anyString())).thenReturn(true, false);

        assertThat(service.me(referrerId).code()).hasSize(6);
        verify(codeRepository, times(2)).existsByCode(anyString());

        when(codeRepository.existsByCode(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.me(referrerId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cliente ou usuario que nao existe pedindo link: 403")
    void meNotForYou() {
        user(clientId, RoleType.CLIENT);
        assertCode(() -> service.me(clientId), HttpStatus.FORBIDDEN, "REFERRAL_NOT_FOR_YOU");
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());
        assertCode(() -> service.me(ghost), HttpStatus.FORBIDDEN, "REFERRAL_NOT_FOR_YOU");
    }

    @Test
    @DisplayName("claim: liga o cliente (codigo sem diferenciar maiuscula e sem espaco)")
    void claimLinks() {
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));
        when(referralRepository.findById(clientId)).thenReturn(Optional.empty());

        ReferralClaimResponse r = service.claim(clientId, " k7m2qx ");

        assertThat(r.code()).isEqualTo("K7M2QX");
        assertThat(r.referredAt()).isNotNull();
        verify(referralRepository).save(argThat(ref -> ref.getClientId().equals(clientId)
                && ref.getReferrerId().equals(referrerId)));
    }

    @Test
    @DisplayName("claim de novo com o mesmo codigo: inofensivo; com outro: 409; o proprio: 422; nao existe: 404")
    void claimRules() {
        OffsetDateTime first = OffsetDateTime.now().minusDays(2);
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", first)));
        when(referralRepository.findById(clientId)).thenReturn(Optional.of(new Referral(clientId, referrerId, first)));

        assertThat(service.claim(clientId, "K7M2QX").referredAt()).isEqualTo(first);

        UUID other = UUID.randomUUID();
        when(codeRepository.findByCode("OTHER2"))
                .thenReturn(Optional.of(new ReferralCode(other, "OTHER2", first)));
        assertCode(() -> service.claim(clientId, "OTHER2"), HttpStatus.CONFLICT, "REFERRAL_ALREADY_SET");

        assertCode(() -> service.claim(referrerId, "K7M2QX"), HttpStatus.UNPROCESSABLE_ENTITY, "REFERRAL_SELF");

        when(codeRepository.findByCode("NOPE22")).thenReturn(Optional.empty());
        assertCode(() -> service.claim(clientId, "NOPE22"), HttpStatus.NOT_FOUND, "REFERRAL_NOT_FOUND");
        verify(referralRepository, never()).save(any());
    }
}
