package com.ospaulos.vupth.domain.establishment.valueObject;

import com.ospaulos.vupth.domain.address.exception.InvalidUfException;
import com.ospaulos.vupth.domain.address.model.Address;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Escopo de localidade da listagem publica — {@code GET /establishment?city=&uf=} (backlog #29,
 * que e a <b>fatia C</b> do #2: o caminho de quem esta SEM coordenada).
 *
 * <p><b>Por que existe.</b> Sem GPS a lista cai na ordenacao por tempo de espera, que e nacional:
 * quem esta em Niteroi ve loja de qualquer lugar do pais. O app ja sabe a cidade/UF do usuario
 * (reverse geocode no device) e so precisava de onde mandar. ⚠️ Isto <b>nao</b> depende de o
 * usuario ter negado a permissao: "permissao concedida" e "tem coordenada agora" sao coisas
 * diferentes — aparelho sem fix, localizacao do sistema desligada, modo aviao ou timeout caem
 * todos aqui.</p>
 *
 * <p><b>Comparacao sem acento, de proposito.</b> A cidade que o app manda vem de geocodificacao
 * reversa ("Sao Goncalo" ou "São Gonçalo", conforme a fonte), e a que esta no banco foi
 * <b>digitada pelo dono</b>. Comparar cru faria a lista vir vazia por causa de uma cedilha — o
 * modo de falha que este produto trata como defeito, porque o usuario le "nao ha lojas". A
 * dobra (acento + caixa) roda em Java pelo mesmo motivo que a distancia da fatia B: a listagem
 * ja faz uma query por establishment para o tempo de espera, entao filtrar no banco pouparia uma
 * fracao irrelevante — e em Java o acento sai de graca, sem extensao {@code unaccent} (que pode
 * nem ser permitida no Postgres free) e sem coluna normalizada.</p>
 *
 * <p>⚠️ <b>Ponta solta conhecida:</b> o filtro {@code ?neighborhood=} continua sendo {@code LIKE}
 * em SQL, portanto <b>sensivel a acento</b> — "Botafogo" acha, "Sao Cristovao" nao acha
 * "São Cristóvão". Nao foi mexido aqui porque e um filtro que os fronts ja usam e mudar seu
 * alcance e decisao propria, nao efeito colateral desta entrega.</p>
 */
public record LocationFilter(String city, String uf) {

    /** Sigla de estado: exatamente 2 letras. E o tamanho da coluna {@code address.state}. */
    private static final Pattern UF = Pattern.compile("[A-Za-z]{2}");

    /** Marcas de acentuacao separadas pela decomposicao NFD. */
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /**
     * Monta o escopo a partir dos parametros crus da query string, ou devolve {@code null} quando
     * nao ha filtro de localidade nenhum — o caminho normal de quem nao mandou nem cidade nem UF.
     *
     * @throws InvalidUfException quando {@code uf} veio e nao e sigla de 2 letras (422)
     */
    public static LocationFilter from(String city, String uf) {
        String normalizedCity = trimToNull(city);
        String normalizedUf = trimToNull(uf);

        if (normalizedUf != null) {
            if (!UF.matcher(normalizedUf).matches()) {
                throw new InvalidUfException(
                        "uf must be the 2-letter state code (received \"" + normalizedUf + "\")");
            }
            normalizedUf = normalizedUf.toUpperCase(Locale.ROOT);
        }

        if (normalizedCity == null && normalizedUf == null) {
            return null;
        }
        return new LocationFilter(normalizedCity, normalizedUf);
    }

    /**
     * true quando o endereco cabe no escopo pedido. {@code city} casa por trecho (como o
     * {@code neighborhood} sempre casou) e {@code uf} casa por igualdade — sigla nao tem
     * "parcial": {@code RJ} dentro de {@code RJ} e a unica leitura possivel, e buscar trecho
     * faria "R" trazer o pais inteiro.
     *
     * <p><b>Endereco ausente e {@code false}</b>, mesma regra do {@code radiusKm}: a pergunta
     * passou a ser "o que fica em X" e uma loja sem endereco nao tem resposta. Sem filtro de
     * localidade ela continua na lista, como sempre esteve.</p>
     */
    public boolean matches(Address address) {
        if (address == null) {
            return false;
        }
        return matchesUf(address.getState()) && matchesCity(address.getCity());
    }

    private boolean matchesUf(String state) {
        return uf == null || (state != null && uf.equalsIgnoreCase(state.trim()));
    }

    private boolean matchesCity(String value) {
        return city == null || (value != null && fold(value).contains(fold(city)));
    }

    /** Tira acento e caixa: "São Gonçalo" e "sao goncalo" viram a mesma coisa. */
    private static String fold(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        return DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
