package com.ospaulos.vupth.domain.establishment.service;

import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.valueObject.BusinessHours;
import com.ospaulos.vupth.domain.establishment.valueObject.DayHours;
import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Matematica pura dos fechamentos para o auto-fechamento (S1 fatia 5 / BUG1).
 *
 * <p>Desde o horario por dia (#2) o fechamento e POR DATA: o de uma data sai do horario dela
 * ({@link BusinessHours#on}) — excecao, senao o dia da semana. Dia fechado ou 24h nao tem fechamento;
 * janela que vira a meia-noite fecha no dia seguinte. Hora de parede no fuso da loja
 * ({@link BusinessClock}; ver BUG2b).</p>
 *
 * <p><b>Ambiguidade fora-do-expediente:</b> um instante fora do horario e ao mesmo tempo "depois do
 * fechamento" e "antes da abertura". Para nao incomodar quem chega CEDO, o gatilho do auto-fechamento
 * so vale na regiao <b>logo apos um fechamento</b> — ate {@code horizon} depois do mais recente.</p>
 */
public final class ClosingTimeMath {

    /** Quantos dias para tras/frente procurar um fechamento (uma semana cobre qualquer grade). */
    private static final int SEARCH_DAYS = 8;

    private ClosingTimeMath() {
    }

    /** A loja tem algum fechamento na semana? (configurada, com ao menos um dia aberto e nao-24h) */
    public static boolean hasClosingEvent(Establishment establishment) {
        BusinessHours hours = establishment.getBusinessHours();
        if (!hours.isConfigured()) {
            return false;
        }
        LocalDate today = BusinessClock.today();
        for (int i = 0; i < SEARCH_DAYS; i++) {
            if (closingOf(hours, today.plusDays(i)) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * O instante {@code now} esta na regiao "logo apos o fechamento"? (fora do horario E a ate
     * {@code horizon} do fechamento mais recente). E o gatilho do prompt + carencia.
     */
    public static boolean isJustAfterClosing(Establishment establishment, OffsetDateTime now, Duration horizon) {
        if (!hasClosingEvent(establishment)) {
            return false;
        }
        if (establishment.isWithinBusinessHours(now)) {
            return false;
        }
        OffsetDateTime recent = mostRecentClosingInstant(establishment, now);
        return recent != null && Duration.between(recent, now).compareTo(horizon) <= 0;
    }

    /** Fechamento mais recente ({@code <= now}), ou {@code null} se nao houver na ultima semana. */
    public static OffsetDateTime mostRecentClosingInstant(Establishment establishment, OffsetDateTime now) {
        BusinessHours hours = establishment.getBusinessHours();
        LocalDate today = BusinessClock.dateAt(now);
        OffsetDateTime best = null;
        for (int i = -SEARCH_DAYS; i <= 0; i++) {
            OffsetDateTime closing = closingOf(hours, today.plusDays(i));
            if (closing != null && !closing.isAfter(now) && (best == null || closing.isAfter(best))) {
                best = closing;
            }
        }
        return best;
    }

    /** Proximo fechamento ({@code > now}), ou {@code null} se nao houver na proxima semana. */
    public static OffsetDateTime nextClosingInstant(Establishment establishment, OffsetDateTime now) {
        BusinessHours hours = establishment.getBusinessHours();
        LocalDate today = BusinessClock.dateAt(now);
        OffsetDateTime best = null;
        for (int i = -1; i <= SEARCH_DAYS; i++) {
            OffsetDateTime closing = closingOf(hours, today.plusDays(i));
            if (closing != null && closing.isAfter(now) && (best == null || closing.isBefore(best))) {
                best = closing;
            }
        }
        return best;
    }

    /** O instante de fechamento do expediente que ABRE em {@code date}, ou {@code null} (fechado / 24h). */
    private static OffsetDateTime closingOf(BusinessHours hours, LocalDate date) {
        DayHours day = hours.on(date);
        if (day == null || day.closed() || day.isAllDay()) {
            return null;
        }
        LocalDate closingDate = day.crossesMidnight() ? date.plusDays(1) : date;
        return BusinessClock.at(closingDate, day.closes());
    }
}
