package com.ospaulos.vupth.domain.address.exception;

/**
 * O geocoding reverso nao devolveu endereco para a coordenada pedida (backlog #2, fatia A2).
 *
 * <p>Cobre os dois casos que o front trata igual: <b>nao ha</b> endereco mapeado ali (meio de uma
 * area sem cadastro no OSM) e o servico <b>nao respondeu</b>. Nos dois, a saida do Partner e a
 * mesma — deixar o dono digitar o endereco na mao. A coordenada continua valida e pode ser enviada
 * junto no cadastro; o reverso e conferencia, nao pre-requisito.</p>
 */
public class ReverseGeocodeNotFoundException extends RuntimeException {

    public ReverseGeocodeNotFoundException(String message) {
        super(message);
    }
}
