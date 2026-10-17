package com.ospaulos.vupth.domain.referral.repository;

import java.math.BigDecimal;
import java.util.UUID;

/** O que falta pagar a uma pessoa numa campanha de indicacao: a soma e quantos creditos. */
public record ReferralPayoutRow(UUID campaignId, UUID beneficiaryId, BigDecimal amount, long credits) {
}
