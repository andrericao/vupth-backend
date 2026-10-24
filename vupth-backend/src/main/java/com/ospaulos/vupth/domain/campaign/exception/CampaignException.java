package com.ospaulos.vupth.domain.campaign.exception;

import org.springframework.http.HttpStatus;

/**
 * Recusa do cupom (DEC-64), com o status e o codigo que o app traduz:
 * <ul>
 *   <li>404 {@code CAMPAIGN_NOT_FOUND} — cupom que nao existe;</li>
 *   <li>409 {@code CAMPAIGN_NOT_AVAILABLE} — desligada, fora da janela ou sem vaga;</li>
 *   <li>409 {@code CAMPAIGN_ENDED} — passou do prazo ({@code ends_at}); vale para todos, inclusive quem ativou o cupom e nao usou (DEC-124);</li>
 *   <li>409 {@code CAMPAIGN_SOLD_OUT} — no prazo, mas sem vaga (esgotada, DEC-124);</li>
 *   <li>409 {@code CAMPAIGN_ALREADY_REDEEMED} — a pessoa ja aceitou;</li>
 *   <li>403 {@code CAMPAIGN_NOT_FOR_YOU} — o perfil nao e o da campanha, ou nao e cliente novo;</li>
 *   <li>422 {@code COUPON_NOT_ACTIVE} — no corte, o cupom nao esta na lista de ativos de quem pede;</li>
 *   <li>422 {@code CAMPAIGN_INVALID} — no painel, campos que nao combinam (janela, cupom, valor, limite);</li>
 *   <li>409 {@code CAMPAIGN_CODE_TAKEN} — no painel, o cupom ja e de outra campanha.</li>
 * </ul>
 */
public class CampaignException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private CampaignException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static CampaignException notFound() {
        return new CampaignException(HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND", "Cupom nao encontrado.");
    }

    public static CampaignException notAvailable() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_AVAILABLE", "Esta campanha nao esta disponivel agora.");
    }

    public static CampaignException ended() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_ENDED", "Esta campanha foi encerrada.");
    }

    public static CampaignException soldOut() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_SOLD_OUT", "As vagas desta campanha esgotaram.");
    }

    public static CampaignException alreadyRedeemed() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_REDEEMED", "Voce ja usou este cupom.");
    }

    public static CampaignException notForYou() {
        return new CampaignException(HttpStatus.FORBIDDEN, "CAMPAIGN_NOT_FOR_YOU", "Este cupom nao vale para o seu perfil.");
    }

    public static CampaignException invalid(String message) {
        return new CampaignException(HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID", message);
    }

    /** Cupom que nao esta na lista de ativos da pessoa (nunca ativado, ja usado, vencido ou fora do dia): o app mostra o aviso. */
    public static CampaignException couponNotActive() {
        return new CampaignException(HttpStatus.UNPROCESSABLE_ENTITY, "COUPON_NOT_ACTIVE",
                "Este cupom nao esta ativo na sua conta (nao foi ativado, ja foi usado, venceu ou nao vale hoje).");
    }

    public static CampaignException codeTaken() {
        return new CampaignException(HttpStatus.CONFLICT, "CAMPAIGN_CODE_TAKEN", "Este cupom ja e de outra campanha.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
