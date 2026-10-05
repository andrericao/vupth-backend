package com.ospaulos.vupth.api.attendance.dto.response;

import java.util.List;

/**
 * Envelope do historico paginado ({@code GET /attendances?page=&size=}, historico-rapido) — o mesmo
 * formato da vitrine paginada ({@code EstablishmentPageResponse}).
 *
 * @param items      os atendimentos desta pagina, no mesmo formato da lista sem paginacao
 * @param page       pagina pedida (comeca em 0)
 * @param size       tamanho da pagina
 * @param totalItems total de atendimentos que passaram nos filtros
 * @param totalPages total de paginas
 * @param hasNext    se existe a pagina seguinte
 */
public record AttendancePageResponse(
        List<AttendanceListResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasNext
) {
}
