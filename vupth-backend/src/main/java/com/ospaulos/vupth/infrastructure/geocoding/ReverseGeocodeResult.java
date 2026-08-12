package com.ospaulos.vupth.infrastructure.geocoding;

/**
 * Endereco por extenso resolvido a partir de uma coordenada (backlog #2, fatia A2 — geocoding
 * REVERSO).
 *
 * <p><b>Para que serve:</b> o dono esta DENTRO da loja quando a cadastra, entao o GPS do aparelho
 * dele e a fonte mais precisa que existe — o CEP resolve a faixa do logradouro, nao a porta. Mas
 * coordenada nua nao da para conferir. O reverso devolve o endereco escrito para o dono <b>ler,
 * reconhecer e polir</b> (numero, complemento) antes de salvar.</p>
 *
 * <p><b>Campos podem vir nulos.</b> O reverso do Nominatim frequentemente nao sabe o numero da casa
 * e as vezes nem o CEP. O contrato aqui e "o que der, com o que nao deu em {@code null}" — quem
 * chama preenche o resto na mao. O {@code displayName} e o unico praticamente sempre presente, e e
 * justamente ele que o dono le para reconhecer o lugar.</p>
 *
 * @param state UF em 2 letras (ex.: {@code "RJ"}), como {@code Address.state} exige
 * @param zipCode CEP em 8 digitos puros, sem hifen, como {@code Address.zipCode} exige
 * @param displayName endereco completo numa linha so, do jeito que o servico o escreve
 */
public record ReverseGeocodeResult(String street,
                                   String number,
                                   String neighborhood,
                                   String city,
                                   String state,
                                   String zipCode,
                                   String displayName) {
}
