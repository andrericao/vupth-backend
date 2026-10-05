package com.ospaulos.vupth.api.affiliation.dto.response;

/**
 * Resultado do convite por e-mail (convite-sem-conta). Dois caminhos:
 * <ul>
 *   <li>{@code INVITATION_SENT} — o e-mail ja e de um profissional: virou o convite comum na hora
 *       ({@code affiliation} preenchido, {@code emailInvitation} nulo);</li>
 *   <li>{@code WAITING_ACCOUNT} — ainda nao ha perfil profissional com esse e-mail: o convite fica
 *       guardado ({@code emailInvitation}) e vira o convite comum quando a pessoa criar o perfil.</li>
 * </ul>
 */
public record InviteByEmailResponse(
        String outcome,
        AffiliationRequestResponse affiliation,
        EmailInvitationResponse emailInvitation
) {
    public static final String INVITATION_SENT = "INVITATION_SENT";
    public static final String WAITING_ACCOUNT = "WAITING_ACCOUNT";
}
