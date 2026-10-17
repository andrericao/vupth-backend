package com.ospaulos.vupth.domain.campaign.model;

/** O beneficio da campanha (DEC-57/61): bonus por indicacao, isencao da taxa do app ou desconto no preco. */
public enum CampaignType {
    REFERRAL_BONUS,
    FEE_WAIVER,
    DISCOUNT;

    /**
     * DEC-76: o ganho e em dinheiro ({@code CASH}) ou em desconto ({@code DISCOUNT}). A indicacao paga em
     * dinheiro (Pix a quem gerou o link); a isencao da taxa e o desconto abatem do preco.
     */
    public String benefit() {
        return this == REFERRAL_BONUS ? "CASH" : "DISCOUNT";
    }
}
