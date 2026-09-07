package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.establishment.exception.InvalidShowcaseFilterException;

import java.util.Arrays;
import java.util.Locale;

/**
 * Ordenacao pedida pela vitrine — {@code GET /establishment?sort=}.
 *
 * <p>Ate 06/09 a ordenacao era <b>implicita</b>: distancia quando o app mandava {@code lat}/{@code lng},
 * tempo de espera quando nao mandava. O usuario nao tinha como escolher, e o app nao tinha como
 * oferecer "mais perto" e "menos espera" como duas opcoes de uma mesma tela. O parametro torna a
 * escolha explicita <b>sem tirar o default</b>: {@code sort} ausente continua se comportando como
 * antes, entao nenhum chamador de hoje muda de resultado.</p>
 *
 * <p>⚠️ <b>Nao ha ordenacao por avaliacao</b> porque nao ha avaliacao: o rating (backlog #6) esta
 * especificado e nao implementado — {@code Establishment} nao tem nota. Quando existir, entra aqui.</p>
 */
public enum EstablishmentSort {

    /** Menor tempo de espera primeiro (nulls last). E o default de quem nao mandou coordenada. */
    WAIT,

    /**
     * Mais perto primeiro. <b>Exige coordenada</b> — sem {@code lat}/{@code lng} nao ha de onde
     * medir, e a listagem cai no {@link #WAIT} <b>sem erro</b>, pelo mesmo motivo que o
     * {@code radiusKm} sem origem e ignorado: quem negou o GPS tem de continuar recebendo lista.
     */
    DISTANCE,

    /** Ordem alfabetica pelo nome da loja, sem acento e sem caixa (Collator pt-BR). */
    NAME;

    /**
     * Le o parametro cru da query string. Aceita <b>qualquer caixa</b> ({@code distance},
     * {@code DISTANCE}) porque o valor e digitado no codigo do app, nao gerado por nos, e uma
     * diferenca de caixa nao e um erro de intencao.
     *
     * @return {@code null} quando o parametro nao veio — a listagem entao usa o default de sempre
     * @throws InvalidShowcaseFilterException quando veio um valor que nao existe (422). Ignorar em
     *         silencio devolveria uma lista <b>ordenada de outro jeito</b> sob um rotulo que o
     *         usuario escolheu na tela — e ninguem, nem o front, perceberia o engano.
     */
    public static EstablishmentSort from(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = raw.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(s -> s.name().equals(wanted))
                .findFirst()
                .orElseThrow(() -> new InvalidShowcaseFilterException(
                        "sort must be one of " + Arrays.toString(values())
                                + " (received \"" + raw + "\")"));
    }
}
