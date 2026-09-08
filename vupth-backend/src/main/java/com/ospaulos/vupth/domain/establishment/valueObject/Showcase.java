package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.establishment.exception.InvalidShowcaseFilterException;

/**
 * O que a vitrine publica pediu alem dos filtros de catalogo — {@code GET /establishment}
 * {@code ?sort=&openNow=&hasAvailability=&maxWaitMinutes=} (06/09).
 *
 * <p><b>Por que existe.</b> No mesmo dia em que a listagem passou a devolver loja <b>fechada</b>
 * (decisao do Andre: quem quer <b>agendar</b> precisa achar a loja que nao esta atendendo agora), o
 * usuario passou a precisar do caminho de volta — "so as abertas", "so quem tem profissional",
 * "espero no maximo 20 min". Sao <b>escolhas dele na tela</b>, e nao mais um default escondido no
 * backend: e exatamente o que o {@code includeAll} era, e ele nao funcionou (ver
 * {@code EstablishmentController#findAll}).</p>
 *
 * <p><b>Todos os campos sao opcionais e combinam</b> entre si e com os filtros que ja existiam
 * (nome, categoria, bairro, cidade/UF, raio). {@link #NONE} e a vitrine sem pedido nenhum — o
 * comportamento identico ao de antes destes parametros.</p>
 *
 * @param sort            ordenacao pedida, ou {@code null} para o default (distancia com
 *                        coordenada, espera sem ela)
 * @param openNow         so lojas <b>abertas agora</b> — pelo {@code is_open} <b>efetivo</b>
 *                        ({@code open_status == OPEN} E dentro do horario), que e o mesmo campo que
 *                        escreve "Aberta" no card. Filtrar pelo {@code open_status} cru deixaria
 *                        passar loja marcada aberta cujo expediente ja acabou
 * @param hasAvailability so lojas com {@code available_professionals > 0}. O Front-Client ja fazia
 *                        este corte <b>no app</b> para as fechadas; virando parametro, ele apaga codigo
 * @param maxWaitMinutes  teto de espera. ⚠️ Loja <b>sem</b> disponibilidade tem espera {@code null}
 *                        e <b>sai</b> da lista quando este filtro vem — "espera desconhecida" nao
 *                        cabe em "espero ate N minutos"
 */
public record Showcase(EstablishmentSort sort,
                       boolean openNow,
                       boolean hasAvailability,
                       Integer maxWaitMinutes) {

    /** Vitrine sem pedido nenhum: o comportamento de quem nao manda nenhum destes parametros. */
    public static final Showcase NONE = new Showcase(null, false, false, null);

    /**
     * Monta a vitrine a partir dos parametros crus da query string.
     *
     * @throws InvalidShowcaseFilterException quando {@code sort} nao existe, ou quando
     *         {@code maxWaitMinutes} e negativo (422). Negativo nao filtra nada que exista:
     *         devolveria lista vazia sempre, e o usuario le isso como "nao ha lojas". O
     *         <b>zero</b> passa de proposito — "so quem atende sem espera" e um pedido legitimo.
     */
    public static Showcase from(String sort, boolean openNow, boolean hasAvailability,
                                Integer maxWaitMinutes) {
        if (maxWaitMinutes != null && maxWaitMinutes < 0) {
            throw new InvalidShowcaseFilterException(
                    "maxWaitMinutes must be zero or greater (received " + maxWaitMinutes + ")");
        }
        return new Showcase(EstablishmentSort.from(sort), openNow, hasAvailability, maxWaitMinutes);
    }

    /** true quando algum filtro de vitrine foi pedido (a ordenacao nao filtra nada). */
    public boolean filters() {
        return openNow || hasAvailability || maxWaitMinutes != null;
    }
}
