package com.ospaulos.vupth.domain.openwatch.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Recusa de um pedido de aviso (avisar-quando-abrir), com o status e o code da resposta. */
@Getter
public class OpenWatchException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private OpenWatchException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static OpenWatchException establishmentNotFound() {
        return new OpenWatchException(HttpStatus.NOT_FOUND, "ESTABLISHMENT_NOT_FOUND", "Estabelecimento nao encontrado");
    }

    public static OpenWatchException professionalNotInEstablishment() {
        return new OpenWatchException(HttpStatus.UNPROCESSABLE_ENTITY, "OPEN_WATCH_PROFESSIONAL_INVALID",
                "O profissional nao trabalha nesta loja");
    }

    public static OpenWatchException notFound() {
        return new OpenWatchException(HttpStatus.NOT_FOUND, "OPEN_WATCH_NOT_FOUND", "Pedido de aviso nao encontrado");
    }

    public static OpenWatchException limit(int max) {
        return new OpenWatchException(HttpStatus.TOO_MANY_REQUESTS, "OPEN_WATCH_LIMIT",
                "Limite de " + max + " pedidos de aviso por dia");
    }
}
