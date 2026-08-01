package com.ospaulos.vupth.domain.address.exception;

/**
 * Latitude/longitude recebida no request nao e um ponto valido do planeta (backlog #2, fatia A2).
 *
 * <p>Ate aqui o backend aceitava qualquer par que chegasse — inclusive {@code (0,0)}, a "ilha nula"
 * no Golfo da Guine, e valores fora do globo. Coordenada errada e pior que coordenada ausente: sem
 * coordenada a loja so nao aparece na busca por raio, com coordenada errada ela aparece <b>no lugar
 * errado</b> e o dono nao tem como perceber.</p>
 */
public class InvalidCoordinatesException extends RuntimeException {

    public InvalidCoordinatesException(String message) {
        super(message);
    }
}
