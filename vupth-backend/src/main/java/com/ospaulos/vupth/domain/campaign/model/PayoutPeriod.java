package com.ospaulos.vupth.domain.campaign.model;

/**
 * DEC-73: de quanto em quanto tempo a bonificacao e paga. O dia ({@code payout_day}) e o da semana na
 * semanal (1 = segunda ... 7 = domingo) e o do mes na mensal (1 a 28, para valer em todo mes).
 */
public enum PayoutPeriod {
    WEEKLY,
    MONTHLY
}
