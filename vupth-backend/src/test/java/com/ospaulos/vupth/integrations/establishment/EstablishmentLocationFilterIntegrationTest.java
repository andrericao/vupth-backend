package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.address.dto.request.AddressRequest;
import com.ospaulos.vupth.infrastructure.geocoding.Geocoder;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — escopo de cidade/UF na vitrine publica (backlog #29, a <b>fatia C</b> do #2).
 *
 * <p>E o caminho de quem esta <b>sem coordenada</b>: permissao negada, aparelho sem fix,
 * localizacao do sistema desligada ou GPS que demorou mais que o timeout. Sem isto a lista sem GPS
 * e nacional — quem esta em Niteroi ve loja de qualquer lugar do pais.</p>
 *
 * <p>Uma das lojas nasce com a cidade <b>acentuada</b> ("São Gonçalo") e outra <b>sem acento</b>,
 * de proposito: a cidade que o app manda vem de geocodificacao reversa e a do banco foi digitada
 * pelo dono — se a comparacao fosse crua, a lista viria vazia por causa de uma cedilha.</p>
 *
 * <p>Como o banco de teste pode ter outras lojas, <b>nenhuma assercao olha a lista inteira</b>:
 * todas filtram os ids criados aqui.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentLocationFilterIntegrationTest {

    private static final double NITEROI_LAT = -22.8833, NITEROI_LNG = -43.1036;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    /** Mockado para o worker de geocoding nao sair da maquina nem preencher coordenada sozinho. */
    @MockBean Geocoder geocoder;

    private final Faker faker = new Faker();

    private String niteroiId;
    private String saoGoncaloId;      // cidade gravada COM acento
    private String saoGoncaloSemId;   // mesma cidade, gravada SEM acento
    private String saoPauloId;
    private String semEnderecoId;
    private String nomeDaLojaDeSp;

    @BeforeAll
    void setup() throws Exception {
        when(geocoder.geocode(any())).thenReturn(Optional.empty());
        when(geocoder.reverse(any())).thenReturn(Optional.empty());

        niteroiId = criarLoja("Niteroi", "RJ", NITEROI_LAT, NITEROI_LNG).id();
        saoGoncaloId = criarLoja("São Gonçalo", "RJ", null, null).id();
        saoGoncaloSemId = criarLoja("Sao Goncalo", "RJ", null, null).id();

        var sp = criarLoja("São Paulo", "SP", -23.5505, -46.6333);
        saoPauloId = sp.id();
        nomeDaLojaDeSp = sp.nome();

        semEnderecoId = criarLojaSemEndereco().id();
    }

    // ─────────────────────────────────────────────────────────────
    // Fatia C — a lista sem GPS deixa de ser nacional
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("sem nenhum filtro: as 5 lojas aparecem — a lista NUNCA vem vazia por falta de GPS")
    void semFiltroNadaSome() throws Exception {
        List<String> ids = idsDoTeste(buscar(""));

        assertThat(ids).contains(niteroiId, saoGoncaloId, saoGoncaloSemId, saoPauloId, semEnderecoId);
    }

    @Test
    @Order(2)
    @DisplayName("?uf=SP: traz so a loja de SP e corta as do RJ")
    void filtraPorUf() throws Exception {
        List<String> ids = idsDoTeste(buscar("&uf=SP"));

        assertThat(ids).containsOnly(saoPauloId);
    }

    @Test
    @Order(3)
    @DisplayName("?uf=rj minusculo funciona — a sigla e normalizada")
    void ufMinuscula() throws Exception {
        List<String> ids = idsDoTeste(buscar("&uf=rj"));

        assertThat(ids).contains(niteroiId, saoGoncaloId, saoGoncaloSemId);
        assertThat(ids).doesNotContain(saoPauloId);
    }

    @Test
    @Order(4)
    @DisplayName("?city=Niteroi: casa por trecho e sem caixa, como o ?neighborhood= sempre fez")
    void filtraPorCidade() throws Exception {
        assertThat(idsDoTeste(buscar("&city=" + enc("niteroi")))).containsOnly(niteroiId);
        assertThat(idsDoTeste(buscar("&city=" + enc("NITEROI")))).containsOnly(niteroiId);
    }

    @Test
    @Order(5)
    @DisplayName("?city=Sao Goncalo SEM acento acha a loja gravada COM acento (e a sem) — e o ponto do filtro")
    void acentoNaoSeparaAsDuasLojas() throws Exception {
        // A cidade vem do reverse geocode do aparelho e a do banco foi digitada pelo dono: comparar
        // cru devolveria meia lista, ou nenhuma, sem que ninguem conseguisse explicar por que.
        List<String> ids = idsDoTeste(buscar("&city=" + enc("Sao Goncalo")));

        assertThat(ids).containsExactlyInAnyOrder(saoGoncaloId, saoGoncaloSemId);
    }

    @Test
    @Order(6)
    @DisplayName("?city=São Gonçalo COM acento acha as mesmas duas — a dobra vale nas duas pontas")
    void acentoNaBuscaTambem() throws Exception {
        List<String> ids = idsDoTeste(buscar("&city=" + enc("São Gonçalo")));

        assertThat(ids).containsExactlyInAnyOrder(saoGoncaloId, saoGoncaloSemId);
    }

    @Test
    @Order(7)
    @DisplayName("cidade + UF juntas: as duas precisam bater")
    void cidadeMaisUf() throws Exception {
        assertThat(idsDoTeste(buscar("&city=" + enc("Niteroi") + "&uf=RJ"))).containsOnly(niteroiId);
        // Combinacao que nao existe: 200 com lista vazia, nunca erro.
        assertThat(idsDoTeste(buscar("&city=" + enc("Niteroi") + "&uf=SP"))).isEmpty();
    }

    @Test
    @Order(8)
    @DisplayName("loja SEM endereco sai quando ha filtro de localidade, e volta quando nao ha")
    void lojaSemEndereco() throws Exception {
        // Mesma regra do radiusKm: a pergunta virou "o que fica em X" e ela nao tem resposta.
        assertThat(idsDoTeste(buscar("&uf=RJ"))).doesNotContain(semEnderecoId);
        assertThat(idsDoTeste(buscar(""))).contains(semEnderecoId);
    }

    // ─────────────────────────────────────────────────────────────
    // Convivencia com os filtros que ja existiam
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("?name= tem PRECEDENCIA sobre o escopo: acha a loja de SP mesmo com uf=RJ")
    void nomeVenceOEscopo() throws Exception {
        // city/uf sao preenchidos pelo APP (a localidade do aparelho); o nome foi digitado pela
        // pessoa. Cruzar os dois devolveria lista vazia por causa de um escopo que ela nao escolheu
        // nem ve na tela — e "nao existe" e "existe, mas nao no seu estado" ficam indistinguiveis.
        List<String> ids = idsDoTeste(buscar("&name=" + enc(nomeDaLojaDeSp) + "&uf=RJ"));

        assertThat(ids).contains(saoPauloId);
    }

    @Test
    @Order(21)
    @DisplayName("escopo combina com o bairro (?neighborhood=) — filtros de localidade se somam")
    void combinaComBairro() throws Exception {
        List<String> ids = idsDoTeste(buscar("&neighborhood=Centro&uf=RJ"));

        assertThat(ids).contains(niteroiId, saoGoncaloId, saoGoncaloSemId);
        assertThat(ids).doesNotContain(saoPauloId);
    }

    @Test
    @Order(22)
    @DisplayName("escopo combina com lat/lng: filtra por UF e a ordenacao por distancia segue valendo")
    void combinaComProximidade() throws Exception {
        // Quem tem GPS normalmente nao manda city/uf, mas mandar os dois nao pode quebrar nada:
        // o escopo corta e a distancia ordena o que sobrou.
        List<Map<String, Object>> lista = buscar("&uf=RJ&lat=" + NITEROI_LAT + "&lng=" + NITEROI_LNG);
        List<String> ids = idsDoTeste(lista);

        assertThat(ids).doesNotContain(saoPauloId);
        assertThat(ids.get(0)).isEqualTo(niteroiId);
        assertThat(((Number) porId(lista, niteroiId).get("distance_km")).doubleValue()).isEqualTo(0.0);
    }

    // ─────────────────────────────────────────────────────────────
    // Validacao da entrada
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("[ERRO] uf que nao e sigla -> 422 ADDRESS_INVALID_UF (nunca casaria com nada)")
    void ufInvalida() throws Exception {
        for (String invalida : List.of("Rio de Janeiro", "SP1", "R")) {
            mvc.perform(get("/establishment?includeAll=true&uf=" + enc(invalida)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("ADDRESS_INVALID_UF"));
        }
    }

    @Test
    @Order(31)
    @DisplayName("city e uf em branco sao ignorados, sem erro e sem esvaziar a lista")
    void camposEmBrancoSaoIgnorados() throws Exception {
        List<String> ids = idsDoTeste(buscar("&city=&uf="));

        assertThat(ids).contains(niteroiId, saoPauloId, semEnderecoId);
    }

    @Test
    @Order(32)
    @DisplayName("o escopo tambem e ANONIMO — sem token, 200")
    void semToken() throws Exception {
        mvc.perform(get("/establishment?includeAll=true&uf=RJ")).andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private record Loja(String id, String nome) {}

    private Loja criarLoja(String cidade, String uf, Double lat, Double lng) throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        var address = new AddressRequest(
                faker.address().streetName(), "100", null, "Centro",
                cidade, uf, "24020000", null, lat, lng);

        mvc.perform(post("/establishments/" + estab.id() + "/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token())
                        .content(mapper.writeValueAsString(address)))
                .andExpect(status().isCreated());

        return new Loja(estab.id(), estab.name());
    }

    /** Loja que ainda nao cadastrou endereco — existe de verdade no produto. */
    private Loja criarLojaSemEndereco() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        return new Loja(estab.id(), estab.name());
    }

    private String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buscar(String queryExtra) throws Exception {
        MvcResult result = mvc.perform(get("/establishment?includeAll=true" + queryExtra))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.readValue(result.getResponse().getContentAsString(), List.class);
    }

    /** Ids desta suite, na ordem em que a API devolveu — ignora o que mais houver no banco. */
    private List<String> idsDoTeste(List<Map<String, Object>> lista) {
        List<String> meus = List.of(niteroiId, saoGoncaloId, saoGoncaloSemId, saoPauloId, semEnderecoId);
        List<String> ordenados = new ArrayList<>();
        for (Map<String, Object> e : lista) {
            String id = String.valueOf(e.get("id"));
            if (meus.contains(id)) {
                ordenados.add(id);
            }
        }
        return ordenados;
    }

    private Map<String, Object> porId(List<Map<String, Object>> lista, String id) {
        return lista.stream()
                .filter(e -> id.equals(String.valueOf(e.get("id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("establishment " + id + " nao veio na listagem"));
    }
}
