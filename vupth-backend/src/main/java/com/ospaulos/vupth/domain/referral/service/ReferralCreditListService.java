package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.api.referral.dto.ReferralCreditItemResponse;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.referral.model.ReferralCredit;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * premio-em-pendentes (André, 05/11): a lista dos premios de indicacao de quem os recebe, cada um com o atendimento que o gerou,
 * para o Partner mostrar em Seu dia -> Pendentes com link para o atendimento.
 */
@Service
@RequiredArgsConstructor
public class ReferralCreditListService {

    private final ReferralCreditRepository creditRepository;
    private final CampaignRepository campaignRepository;
    private final AttendanceRepository attendanceRepository;

    @Transactional(readOnly = true)
    public List<ReferralCreditItemResponse> credits(UUID userId) {
        List<ReferralCredit> credits = creditRepository.findByBeneficiaryIdOrderByCreatedAtDesc(userId);
        if (credits.isEmpty()) {
            return List.of();
        }
        Map<UUID, Campaign> campaigns = campaignRepository.findAllById(
                        credits.stream().map(ReferralCredit::getCampaignId).distinct().toList())
                .stream().collect(Collectors.toMap(Campaign::getId, Function.identity()));
        Map<UUID, Attendance> attendances = attendanceRepository.findAllByIdsWithDetails(
                        credits.stream().map(ReferralCredit::getAttendanceId).distinct().toList())
                .stream().collect(Collectors.toMap(Attendance::getId, Function.identity(), (a, b) -> a));
        return credits.stream().map(c -> {
            Campaign campaign = campaigns.get(c.getCampaignId());
            Attendance attendance = attendances.get(c.getAttendanceId());
            return new ReferralCreditItemResponse(c.getId(), c.getCampaignId(), campaign == null ? null : campaign.getName(),
                    c.getAmount(), c.getShare().name(), ReferralCreditItemResponse.statusOf(c.getReleasedAt(), c.getPaidAt()),
                    c.getAttendanceId(), clientFirstName(attendance), attendance == null ? null : attendance.getEndTime(),
                    c.getCreatedAt(), c.getReleasedAt(), c.getPaidAt());
        }).toList();
    }

    private static String clientFirstName(Attendance attendance) {
        if (attendance == null || attendance.getClient() == null || attendance.getClient().getBaseUser() == null) {
            return null;
        }
        String first = attendance.getClient().getBaseUser().getFirstName();
        if (first == null) {
            return null;
        }
        String trimmed = first.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }
}
