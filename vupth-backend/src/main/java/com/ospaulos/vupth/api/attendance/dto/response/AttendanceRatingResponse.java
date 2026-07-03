package com.ospaulos.vupth.api.attendance.dto.response;

import com.ospaulos.vupth.domain.attendance.model.AttendanceRating;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Resposta apos avaliar um atendimento (item #6). Devolve o voto criado e um retrato imediato das
 * medias denormalizadas para o front exibir sem uma nova leitura:
 * <ul>
 *   <li>{@code professionalRatingAverage}/{@code professionalRatingCount} — sempre atualizados.</li>
 *   <li>{@code serviceItemRatingAverage}/{@code serviceItemRatingCount} — presentes so quando o
 *       atendimento tinha exatamente 1 servico (senao null / 0).</li>
 * </ul>
 */
public record AttendanceRatingResponse(
        UUID id,
        UUID attendanceId,
        int score,
        String comment,
        UUID serviceItemId,
        OffsetDateTime createdAt,
        Double professionalRatingAverage,
        long professionalRatingCount,
        Double serviceItemRatingAverage,
        long serviceItemRatingCount
) {

    public static AttendanceRatingResponse of(AttendanceRating rating,
                                              Professional professional,
                                              ServiceItem serviceItem) {
        return new AttendanceRatingResponse(
                rating.getId(),
                rating.getAttendanceId(),
                rating.getScore(),
                rating.getComment(),
                rating.getServiceItemId(),
                rating.getCreatedAt(),
                professional.getRatingAverage(),
                professional.getRatingCount(),
                serviceItem != null ? serviceItem.getRatingAverage() : null,
                serviceItem != null ? serviceItem.getRatingCount() : 0L
        );
    }
}
