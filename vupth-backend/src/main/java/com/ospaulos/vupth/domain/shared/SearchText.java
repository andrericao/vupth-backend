package com.ospaulos.vupth.domain.shared;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Busca pelo nome sem acento e sem caixa (busca-sem-acento, Partner 24/09): "joao" acha "João".
 *
 * O termo digitado e dobrado aqui ({@link #fold}); a coluna e dobrada no banco com
 * {@code translate(LOWER(col), ACCENTED, PLAIN)} — o {@code translate} e nativo do Postgres, sem a
 * extensao {@code unaccent}. O LOWER do Postgres pode nao baixar letra acentuada (depende do locale do
 * banco), por isso o ACCENTED traz as maiusculas e o PLAIN leva tudo para minuscula. As duas pontas precisam tirar os mesmos acentos: quem mexer em uma,
 * mexe na outra (as queries repetem {@link #ACCENTED} e {@link #PLAIN} como literal).
 */
public final class SearchText {

    /** Acentos que a coluna perde no banco — maiusculas e minusculas, todos para a letra minuscula. */
    public static final String ACCENTED = "áàâãäåéèêëíìîïóòôõöúùûüçñýÁÀÂÃÄÅÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇÑÝ";
    public static final String PLAIN = "aaaaaaeeeeiiiiooooouuuucnyaaaaaaeeeeiiiiooooouuuucny";

    private SearchText() {
    }

    /** Termo de busca sem acento, em minusculas e sem espacos nas pontas; {@code null} continua null. */
    public static String fold(String term) {
        if (term == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(term.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
