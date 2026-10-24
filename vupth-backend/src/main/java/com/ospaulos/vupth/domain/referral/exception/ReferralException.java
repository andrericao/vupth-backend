package com.ospaulos.vupth.domain.referral.exception;

import org.springframework.http.HttpStatus;

/**
 * Recusa da indicacao (BAK-9), com o status e o codigo que o app traduz:
 * <ul>
 *   <li>404 {@code REFERRAL_NOT_FOUND} — codigo que nao existe;</li>
 *   <li>409 {@code REFERRAL_ALREADY_SET} — o cliente ja foi indicado por outra pessoa (o primeiro vale);</li>
 *   <li>422 {@code REFERRAL_SELF} — o proprio codigo;</li>
 *   <li>403 {@code REFERRAL_NOT_FOR_YOU} — so profissional e dono tem codigo;</li>
 *   <li>404 {@code REFERRAL_NOTHING_TO_RELEASE} — no painel, nenhum premio travado para liberar;</li>
 *   <li>404 {@code REFERRAL_NOTHING_TO_PAY} — no painel, nada em aberto para a pessoa na campanha.</li>
 * </ul>
 */
public class ReferralException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private ReferralException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ReferralException notFound() {
        return new ReferralException(HttpStatus.NOT_FOUND, "REFERRAL_NOT_FOUND", "Codigo de indicacao nao encontrado.");
    }

    public static ReferralException alreadySet() {
        return new ReferralException(HttpStatus.CONFLICT, "REFERRAL_ALREADY_SET", "Voce ja foi indicado por outra pessoa.");
    }

    public static ReferralException self() {
        return new ReferralException(HttpStatus.UNPROCESSABLE_ENTITY, "REFERRAL_SELF", "Este codigo e o seu.");
    }

    public static ReferralException notForYou() {
        return new ReferralException(HttpStatus.FORBIDDEN, "REFERRAL_NOT_FOR_YOU", "Usuario nao encontrado para o link de indicacao.");
    }

    public static ReferralException nothingToPay() {
        return new ReferralException(HttpStatus.NOT_FOUND, "REFERRAL_NOTHING_TO_PAY", "Nada a pagar a esta pessoa nesta campanha.");
    }

    public static ReferralException nothingToRelease() {
        return new ReferralException(HttpStatus.NOT_FOUND, "REFERRAL_NOTHING_TO_RELEASE", "Nenhum premio travado para liberar nesta campanha.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
