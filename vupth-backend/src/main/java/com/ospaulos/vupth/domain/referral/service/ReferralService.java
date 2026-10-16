package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.api.referral.dto.ReferralClaimResponse;
import com.ospaulos.vupth.api.referral.dto.ReferralMeResponse;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.model.Referral;
import com.ospaulos.vupth.domain.referral.model.ReferralCode;
import com.ospaulos.vupth.domain.referral.model.ReferralShare;
import com.ospaulos.vupth.domain.referral.repository.ReferralCodeRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Bonificacao por link (BAK-9, DEC-52/70/73), a ligacao: cada profissional e cada dono tem um codigo fixo; o
 * cliente que chega pelo link fica ligado a quem o indicou. Vale o primeiro codigo, que nao muda depois. O
 * credito por corte concluido usa esta ligacao e os parametros da campanha de indicacao.
 */
@Slf4j
@Service
@Transactional
public class ReferralService {

    /** Sem 0/O, 1/I/L: o codigo e lido e digitado por gente. */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 5;

    private final ReferralCodeRepository codeRepository;
    private final ReferralRepository referralRepository;
    private final ReferralCreditRepository creditRepository;
    private final UserRepository userRepository;
    private final String linkBase;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public ReferralService(ReferralCodeRepository codeRepository, ReferralRepository referralRepository,
                           ReferralCreditRepository creditRepository, UserRepository userRepository,
                           @Value("${vupth.referral.link-base:https://vupth.pages.dev/i/}") String linkBase) {
        this.codeRepository = codeRepository;
        this.referralRepository = referralRepository;
        this.creditRepository = creditRepository;
        this.userRepository = userRepository;
        this.linkBase = linkBase;
    }

    /** O link de quem indica; o codigo nasce no primeiro pedido. So profissional e dono. */
    public ReferralMeResponse me(UUID userId) {
        BaseUser user = userRepository.findById(userId).orElseThrow(ReferralException::notForYou);
        if (!user.hasRole(RoleType.PROFESSIONAL) && !user.hasRole(RoleType.OWNER)) {
            throw ReferralException.notForYou();
        }
        ReferralCode code = codeRepository.findById(userId).orElseGet(() -> create(userId));
        return new ReferralMeResponse(code.getCode(), linkBase + code.getCode(), referralRepository.countByReferrerId(userId),
                creditRepository.countClientsByBeneficiaryAndShareIn(userId, List.of(ReferralShare.REFERRER, ReferralShare.OWNER)),
                creditRepository.sumUnpaidByBeneficiary(userId));
    }

    /** Liga o cliente a quem gerou o codigo. Repetir o mesmo codigo e inofensivo; outro codigo, 409. */
    public ReferralClaimResponse claim(UUID clientId, String rawCode) {
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        ReferralCode code = codeRepository.findByCode(normalized).orElseThrow(ReferralException::notFound);
        if (code.getUserId().equals(clientId)) {
            throw ReferralException.self();
        }
        Optional<Referral> existing = referralRepository.findById(clientId);
        if (existing.isPresent()) {
            if (!existing.get().getReferrerId().equals(code.getUserId())) {
                throw ReferralException.alreadySet();
            }
            return new ReferralClaimResponse(code.getCode(), existing.get().getCreatedAt());
        }
        Referral referral = referralRepository.save(new Referral(clientId, code.getUserId(), OffsetDateTime.now(clock)));
        log.info("[Referral] cliente={} indicado por={}", clientId, code.getUserId());
        return new ReferralClaimResponse(code.getCode(), referral.getCreatedAt());
    }

    private ReferralCode create(UUID userId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!codeRepository.existsByCode(candidate)) {
                log.info("[Referral] codigo criado para user={}", userId);
                return codeRepository.save(new ReferralCode(userId, candidate, OffsetDateTime.now(clock)));
            }
        }
        throw new IllegalStateException("Nao foi possivel gerar um codigo de indicacao unico");
    }

    String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
