package com.ospaulos.vupth.domain.attendance.fitin.exception;

import org.springframework.http.HttpStatus;

/**
 * Recusas do pedido de encaixe (encaixe-diluido): 404 {@code FIT_IN_NOT_FOUND} (tambem para quem nao e o cliente nem o
 * profissional do pedido, DEC-141), 409 {@code FIT_IN_CLOSED} (ja decidido ou vencido), 409 {@code FIT_IN_NOT_YOUR_TURN}, 409
 * {@code FIT_IN_ONLY_TODAY}, 409 {@code FIT_IN_ALREADY_OPEN} e 422 {@code FIT_IN_INVALID}.
 */
public class FitInException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private FitInException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }

    public String getCode() { return code; }

    public static FitInException notFound() {
        return new FitInException(HttpStatus.NOT_FOUND, "FIT_IN_NOT_FOUND", "Pedido de encaixe nao encontrado.");
    }

    public static FitInException closed() {
        return new FitInException(HttpStatus.CONFLICT, "FIT_IN_CLOSED", "Este pedido de encaixe ja foi decidido ou venceu.");
    }

    public static FitInException notYourTurn() {
        return new FitInException(HttpStatus.CONFLICT, "FIT_IN_NOT_YOUR_TURN", "Agora quem responde e a outra parte.");
    }

    public static FitInException onlyToday() {
        return new FitInException(HttpStatus.CONFLICT, "FIT_IN_ONLY_TODAY", "O encaixe vale so para a fila de hoje, num horario que ainda nao passou.");
    }

    public static FitInException alreadyOpen() {
        return new FitInException(HttpStatus.CONFLICT, "FIT_IN_ALREADY_OPEN", "Voce ja tem um pedido de encaixe aberto com este profissional.");
    }

    public static FitInException invalid(String message) {
        return new FitInException(HttpStatus.UNPROCESSABLE_ENTITY, "FIT_IN_INVALID", message);
    }
}
