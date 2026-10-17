package com.ospaulos.vupth.domain.referral.model;

/**
 * Quem recebe o credito do corte de um cliente indicado (BAK-9, DEC-70/73): {@code REFERRER} — quem gerou o
 * link, com o valor inteiro; na loja do dono que nao atendeu, {@code OWNER} (o dono que gerou o link) e
 * {@code PROFESSIONAL} (quem atendeu) dividem pelos percentuais da campanha.
 */
public enum ReferralShare {
    REFERRER,
    OWNER,
    PROFESSIONAL
}
