package com.ospaulos.vupth.api.establishment.dto.response;

import com.ospaulos.vupth.domain.establishment.valueObject.ShowcasePage;

import java.util.List;

/**
 * Envelope da vitrine paginada ({@code GET /establishment?page=&size=}, BAK-2).
 *
 * @param items      as lojas desta pagina, no mesmo formato da lista sem paginacao
 * @param page       pagina pedida (comeca em 0)
 * @param size       tamanho da pagina
 * @param totalItems total de lojas que passaram nos filtros
 * @param totalPages total de paginas
 * @param hasNext    se existe a pagina seguinte
 */
public record EstablishmentPageResponse(
        List<EstablishmentResponse> items,
        int page,
        int size,
        int totalItems,
        int totalPages,
        boolean hasNext
) {

    public static EstablishmentPageResponse of(List<EstablishmentResponse> all, ShowcasePage page) {
        int totalPages = page.totalPages(all.size());
        return new EstablishmentPageResponse(page.slice(all), page.page(), page.size(),
                all.size(), totalPages, page.page() + 1 < totalPages);
    }
}
