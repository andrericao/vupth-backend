package com.ospaulos.vupth.domain.shared;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Relogio de NEGOCIO — o fuso em que a loja "pensa" (BUG2b, 2026-07-26).
 *
 * <p>Horario de funcionamento ({@code opening_time}/{@code closing_time}) e horario de refeicao
 * ({@code fixed_start_time}) sao <b>relogio de parede</b>: "a loja abre 08:00" nao carrega offset
 * nenhum — 08:00 e 08:00 no fuso da loja. Por isso esses campos sao {@link LocalTime} (coluna
 * {@code TIME}) e nao {@code OffsetTime}: uma coluna {@code TIME} nao tem onde guardar offset, e o
 * Hibernate era obrigado a descartar o dele normalizando para UTC (gravava 12:00-03:00 como 15:00 e
 * devolvia "15:00Z" — o bug que o front-partner reportou).</p>
 *
 * <p>Como o dado deixou de carregar offset, toda comparacao com "agora" (e todo recorte de "hoje")
 * precisa de um fuso explicito: e este. O produto e single-timezone; o fuso vem de
 * {@code vupth.timezone} (default {@code America/Sao_Paulo}) e e injetado no boot por
 * {@code BusinessClockConfig}. Entidades de dominio nao recebem injecao do Spring, por isso o
 * valor mora aqui como estado estatico configuravel.</p>
 */
public final class BusinessClock {

    /** Fuso de negocio default — usado tambem em teste, onde o Spring pode nao ter subido. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("America/Sao_Paulo");

    private static volatile ZoneId zone = DEFAULT_ZONE;

    private BusinessClock() {
    }

    /** Define o fuso de negocio (chamado uma vez no boot por {@code BusinessClockConfig}). */
    public static void configure(ZoneId newZone) {
        zone = newZone != null ? newZone : DEFAULT_ZONE;
    }

    public static ZoneId zone() {
        return zone;
    }

    /** Hora de parede AGORA no fuso da loja — o que comparar com opening/closing/fixedStart. */
    public static LocalTime nowTime() {
        return LocalTime.now(zone);
    }

    /** Dia corrente no fuso da loja (o "hoje" das refeicoes: vira a meia-noite LOCAL, nao a UTC). */
    public static LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Hora de parede que o instante {@code at} representa no fuso da loja. */
    public static LocalTime timeAt(OffsetDateTime at) {
        return at.atZoneSameInstant(zone).toLocalTime();
    }

    /** Dia (no fuso da loja) em que o instante {@code at} cai. */
    public static LocalDate dateAt(OffsetDateTime at) {
        return at.atZoneSameInstant(zone).toLocalDate();
    }

    /**
     * Instante correspondente ao relogio de parede {@code date}+{@code time} no fuso da loja —
     * caminho inverso de {@link #timeAt(OffsetDateTime)}. Em horario de verao/gap o
     * {@code ZonedDateTime} ja resolve pela regra do fuso.
     */
    public static OffsetDateTime at(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(zone).toOffsetDateTime();
    }
}
