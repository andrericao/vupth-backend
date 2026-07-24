package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.domain.establishment.model.Establishment;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;

/**
 * Matematica pura da janela de expediente para o auto-fechamento (S1 fatia 5 / BUG1).
 *
 * <p>O {@code closing_time} e um horario RECORRENTE (hora do dia, {@link OffsetTime}) — nao um
 * instante. Estes helpers convertem a recorrencia em instantes concretos, sempre normalizando
 * offsets para UTC (mesma disciplina de {@code Establishment.isWithinBusinessHours}; ver BUG2
 * fuso). Sem janela definida ({@code opening}/{@code closing} nulos) ou 24h ({@code open == close})
 * nao existe "fechamento" — nada a disparar.</p>
 *
 * <p><b>Ambiguidade fora-do-expediente:</b> um instante fora da janela e ao mesmo tempo "depois do
 * fechamento de hoje" e "antes da abertura de amanha". Para nao incomodar quem chega CEDO (antes de
 * abrir), o gatilho do auto-fechamento so vale na regiao <b>logo apos o fechamento</b> — ate
 * {@code horizon} depois da ocorrencia mais recente do {@code closing_time}.</p>
 */
public final class ClosingTimeMath {

    private ClosingTimeMath() {
    }

    /** A loja tem um fechamento diario real? (janela definida e nao-24h) */
    public static boolean hasClosingEvent(Establishment establishment) {
        OffsetTime opening = establishment.getOpeningTime();
        OffsetTime closing = establishment.getClosingTime();
        if (opening == null || closing == null) {
            return false;
        }
        return !toUtc(opening).equals(toUtc(closing)); // open == close = 24h, nunca fecha
    }

    /**
     * O instante {@code now} esta na regiao "logo apos o fechamento"? (fora da janela E a ate
     * {@code horizon} da ocorrencia mais recente do {@code closing_time}). E o gatilho do prompt +
     * carencia: quem esta em expediente aqui deveria estar fechando.
     */
    public static boolean isJustAfterClosing(Establishment establishment, OffsetDateTime now, Duration horizon) {
        if (!hasClosingEvent(establishment)) {
            return false;
        }
        OffsetTime nowTime = now.toOffsetTime();
        if (establishment.isWithinBusinessHours(nowTime)) {
            return false;
        }
        Duration since = Duration.between(mostRecentClosingInstant(establishment, now), now);
        return since.compareTo(horizon) <= 0;
    }

    /** Ocorrencia mais recente ({@code <= now}) do {@code closing_time}, em UTC. */
    public static OffsetDateTime mostRecentClosingInstant(Establishment establishment, OffsetDateTime now) {
        OffsetDateTime nowUtc = now.withOffsetSameInstant(ZoneOffset.UTC);
        LocalTime close = toUtc(establishment.getClosingTime());
        OffsetDateTime candidate = nowUtc.toLocalDate().atTime(close).atOffset(ZoneOffset.UTC);
        return candidate.isAfter(nowUtc) ? candidate.minusDays(1) : candidate;
    }

    /** Proxima ocorrencia ({@code > now}) do {@code closing_time}, em UTC. */
    public static OffsetDateTime nextClosingInstant(Establishment establishment, OffsetDateTime now) {
        return mostRecentClosingInstant(establishment, now).plusDays(1);
    }

    private static LocalTime toUtc(OffsetTime t) {
        return t.withOffsetSameInstant(ZoneOffset.UTC).toLocalTime();
    }
}
