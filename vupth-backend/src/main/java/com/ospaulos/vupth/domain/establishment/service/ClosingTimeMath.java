package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * Matematica pura da janela de expediente para o auto-fechamento (S1 fatia 5 / BUG1).
 *
 * <p>O {@code closing_time} e um horario RECORRENTE de PAREDE (hora do dia, {@link LocalTime}) — nao
 * um instante. Estes helpers convertem a recorrencia em instantes concretos usando o fuso da loja
 * ({@link BusinessClock}; ver BUG2b — antes o campo era {@code OffsetTime} e tudo era normalizado
 * para UTC, o que deslocava o dado gravado). Sem janela definida ({@code opening}/{@code closing}
 * nulos) ou 24h ({@code open == close}) nao existe "fechamento" — nada a disparar.</p>
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
        LocalTime opening = establishment.getOpeningTime();
        LocalTime closing = establishment.getClosingTime();
        if (opening == null || closing == null) {
            return false;
        }
        return !opening.equals(closing); // open == close = 24h, nunca fecha
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
        if (establishment.isWithinBusinessHours(BusinessClock.timeAt(now))) {
            return false;
        }
        Duration since = Duration.between(mostRecentClosingInstant(establishment, now), now);
        return since.compareTo(horizon) <= 0;
    }

    /** Ocorrencia mais recente ({@code <= now}) do {@code closing_time}, no fuso da loja. */
    public static OffsetDateTime mostRecentClosingInstant(Establishment establishment, OffsetDateTime now) {
        LocalTime close = establishment.getClosingTime();
        OffsetDateTime candidate = BusinessClock.at(BusinessClock.dateAt(now), close);
        return candidate.isAfter(now) ? BusinessClock.at(BusinessClock.dateAt(now).minusDays(1), close) : candidate;
    }

    /** Proxima ocorrencia ({@code > now}) do {@code closing_time}, no fuso da loja. */
    public static OffsetDateTime nextClosingInstant(Establishment establishment, OffsetDateTime now) {
        OffsetDateTime recent = mostRecentClosingInstant(establishment, now);
        return BusinessClock.at(BusinessClock.dateAt(recent).plusDays(1), establishment.getClosingTime());
    }
}
