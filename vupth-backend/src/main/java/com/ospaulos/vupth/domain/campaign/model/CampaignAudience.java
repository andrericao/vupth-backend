package com.ospaulos.vupth.domain.campaign.model;

import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;

/** DEC-64: o perfil de quem pode aceitar o cupom. */
public enum CampaignAudience {
    CLIENT(RoleType.CLIENT),
    PROFESSIONAL(RoleType.PROFESSIONAL),
    OWNER(RoleType.OWNER);

    private final RoleType role;

    CampaignAudience(RoleType role) {
        this.role = role;
    }

    public RoleType role() {
        return role;
    }
}
