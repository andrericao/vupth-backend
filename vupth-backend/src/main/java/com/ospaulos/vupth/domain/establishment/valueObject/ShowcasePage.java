package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.establishment.exception.InvalidShowcaseFilterException;

import java.util.List;

/**
 * Pagina da vitrine ({@code GET /establishment?page=&size=}, BAK-2 · 03/10).
 *
 * <p><b>Opt-in:</b> sem {@code page} nem {@code size} a listagem segue devolvendo a lista inteira,
 * como antes — o app em campo nao quebra ate migrar. Com um dos dois, volta o envelope paginado.
 * O corte e feito <b>depois</b> dos filtros e da ordenacao (abertas primeiro, espera ou distancia),
 * entao a pagina 0 e sempre o topo da vitrine que o usuario veria.</p>
 */
public record ShowcasePage(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    /**
     * @return {@code null} quando nao veio nem {@code page} nem {@code size} (lista inteira)
     * @throws InvalidShowcaseFilterException {@code page < 0} ou {@code size} fora de 1..100 (422)
     */
    public static ShowcasePage from(Integer page, Integer size) {
        if (page == null && size == null) {
            return null;
        }
        int p = page != null ? page : 0;
        int s = size != null ? size : DEFAULT_SIZE;
        if (p < 0) {
            throw new InvalidShowcaseFilterException("page must be >= 0, got " + p);
        }
        if (s < 1 || s > MAX_SIZE) {
            throw new InvalidShowcaseFilterException("size must be between 1 and " + MAX_SIZE + ", got " + s);
        }
        return new ShowcasePage(p, s);
    }

    /** Os itens desta pagina dentro da lista ja filtrada e ordenada. */
    public <T> List<T> slice(List<T> all) {
        long from = (long) page * size;
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList((int) from, (int) Math.min(from + size, all.size()));
    }

    public int totalPages(int totalItems) {
        return (totalItems + size - 1) / size;
    }
}
