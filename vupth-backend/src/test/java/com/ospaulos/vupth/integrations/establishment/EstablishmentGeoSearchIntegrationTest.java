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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — busca por proximidade na vitrine publica (backlog #2, fatia B).
 *
 * <p><b>Decisao de produto (Andre 2026-07-27, opcao A): NAO ha raio default.</b> Com {@code lat}/
 * {@code lng} e sem {@code radiusKm} a lista devolve TUDO reordenado por distancia; o raio e filtro
 * opcional e explicito. Um raio default deixaria a tela vazia para quem esta numa regiao sem loja —
 * e o usuario nao distingue isso de "o app quebrou".</p>
 *
 * <p>As lojas do teste ficam nos mesmos 4 pontos do seed (Penha, Duque de Caxias, Niteroi) mais uma
 * <b>sem coordenada</b>, que e o caso real de quem cadastrou por texto e o geocoding ainda nao
 * resolveu. Como o banco de teste pode ter outras lojas, <b>nenhuma assercao olha a lista inteira</b>:
 * todas filtram os ids criados aqui e conferem a ordem RELATIVA entre eles.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentGeoSearchIntegrationTest {

    // Os mesmos pontos do seed (10-stubs/012-seed-data.yaml).
    private static final double PENHA_LAT = -22.8422, PENHA_LNG = -43.2764;
    private static final double CAXIAS_LAT = -22.7858, CAXIAS_LNG = -43.3117;   // ~7,2 km da Penha
    private static final double NITEROI_LAT = -22.8833, NITEROI_LNG = -43.1036; // ~18,3 km da Penha

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    /** Mockado para que o worker de geocoding nunca resolva sozinho a loja que deve ficar SEM ponto. */
    @MockBean Geocoder geocoder;

    private final Faker faker = new Faker();

    private String penhaId;
    private String penhaGemeaId;   // mesma coordenada da Penha — existe so para testar o desempate
    private String caxiasId;
    private String niteroiId;
    private String semCoordenadaId;

    @BeforeAll
    void setup() throws Exception {
        when(geocoder.geocode(any())).thenReturn(Optional.empty());
        when(geocoder.reverse(any())).thenReturn(Optional.empty());

        penhaId = criarLoja(PENHA_LAT, PENHA_LNG).id;
        penhaGemeaId = criarLoja(PENHA_LAT, PENHA_LNG).id;
        caxiasId = criarLoja(CAXIAS_LAT, CAXIAS_LNG).id;
        niteroiId = criarLoja(NITEROI_LAT, NITEROI_LNG).id;

        semCoordenadaId = criarLoja(null, null).id;
    }

    // ─────────────────────────────────────────────────────────────
    // Sem coordenada na busca — o comportamento de sempre
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("sem lat/lng: 200 e distance_km ausente — a ordenacao por tempo de espera segue valendo")
    void semCoordenadaNaBusca() throws Exception {
        List<Map<String, Object>> lista = buscar("");

        assertThat(idsDoTeste(lista)).contains(penhaId, caxiasId, niteroiId, semCoordenadaId);
        assertThat(lista).allSatisfy(e -> assertThat(e.get("distance_km")).isNull());
    }

    @Test
    @Order(2)
    @DisplayName("radiusKm SEM lat/lng e ignorado (200), nao vira erro nem lista vazia")
    void raioSemOrigemEIgnorado() throws Exception {
        // O app pode mandar um raio guardado de antes com a permissao de localizacao NEGADA agora.
        // A lista nunca pode vir vazia por causa disso.
        List<Map<String, Object>> lista = buscar("&radiusKm=1");

        assertThat(idsDoTeste(lista)).contains(penhaId, niteroiId, semCoordenadaId);
        assertThat(lista).allSatisfy(e -> assertThat(e.get("distance_km")).isNull());
    }

    // ─────────────────────────────────────────────────────────────
    // Ordenacao por distancia (sem raio)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("com lat/lng: ordena por distancia e a loja SEM coordenada vai para o FIM, nao some")
    void ordenaPorDistancia() throws Exception {
        List<String> ids = idsDoTeste(buscar(geo(PENHA_LAT, PENHA_LNG)));

        assertThat(ids).containsSubsequence(caxiasId, niteroiId, semCoordenadaId);
        assertThat(ids.indexOf(penhaId)).isLessThan(ids.indexOf(caxiasId));
        // Sem raio ninguem some: quem nao tem ponto no mapa ainda e uma loja de verdade.
        assertThat(ids).contains(semCoordenadaId);
    }

    @Test
    @Order(11)
    @DisplayName("distance_km vem preenchido, arredondado, e bate com a matriz do seed")
    void distanciaExposta() throws Exception {
        Map<String, Object> caxias = porId(buscar(geo(PENHA_LAT, PENHA_LNG)), caxiasId);
        Map<String, Object> penha = porId(buscar(geo(PENHA_LAT, PENHA_LNG)), penhaId);
        Map<String, Object> semCoord = porId(buscar(geo(PENHA_LAT, PENHA_LNG)), semCoordenadaId);

        assertThat(((Number) caxias.get("distance_km")).doubleValue()).isBetween(6.0, 8.5);
        assertThat(((Number) penha.get("distance_km")).doubleValue()).isEqualTo(0.0);
        // Distinto de "0 km": a loja nao tem onde ser colocada no mapa.
        assertThat(semCoord.get("distance_km")).isNull();
    }

    @Test
    @Order(12)
    @DisplayName("empate de distancia desempata por id — ordem ESTAVEL entre requests")
    void ordemEstavelNoEmpate() throws Exception {
        // Sem o desempate, duas lojas equidistantes trocariam de lugar entre chamadas e a paginacao
        // (backlog) repetiria ou pularia item.
        List<String> primeira = idsDoTeste(buscar(geo(PENHA_LAT, PENHA_LNG)));
        List<String> segunda = idsDoTeste(buscar(geo(PENHA_LAT, PENHA_LNG)));

        assertThat(primeira).isEqualTo(segunda);

        // O desempate compara o id como TEXTO (ordem hexadecimal, sem sinal) — que e a ordem do
        // Postgres. Se o service usasse UUID.compareTo, que compara long COM sinal, um id comecando
        // em 8-f viria antes de um comecando em 0-7 e a lista mudaria de ordem no dia em que a
        // ordenacao migrar para SQL.
        String menor = penhaId.compareTo(penhaGemeaId) < 0 ? penhaId : penhaGemeaId;
        String maior = menor.equals(penhaId) ? penhaGemeaId : penhaId;
        assertThat(primeira).containsSubsequence(menor, maior);
    }

    // ─────────────────────────────────────────────────────────────
    // Filtro por raio (explicito)
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("radiusKm=10: pega Penha e Caxias, corta Niteroi E a loja sem coordenada")
    void raioDeDezKm() throws Exception {
        List<String> ids = idsDoTeste(buscar(geo(PENHA_LAT, PENHA_LNG) + "&radiusKm=10"));

        assertThat(ids).contains(penhaId, caxiasId);
        assertThat(ids).doesNotContain(niteroiId);
        // Com raio explicito a loja sem ponto SAI: nao da para afirmar que ela cabe em "ate 10km".
        assertThat(ids).doesNotContain(semCoordenadaId);
    }

    @Test
    @Order(21)
    @DisplayName("radiusKm=20: alcanca Niteroi tambem; a sem coordenada continua fora")
    void raioDeVinteKm() throws Exception {
        List<String> ids = idsDoTeste(buscar(geo(PENHA_LAT, PENHA_LNG) + "&radiusKm=20"));

        assertThat(ids).contains(penhaId, caxiasId, niteroiId);
        assertThat(ids).doesNotContain(semCoordenadaId);
    }

    @Test
    @Order(22)
    @DisplayName("raio minusculo nao devolve nem a loja da propria coordenada de origem por engano")
    void raioMinusculo() throws Exception {
        List<String> ids = idsDoTeste(buscar(geo(CAXIAS_LAT, CAXIAS_LNG) + "&radiusKm=0.5"));

        assertThat(ids).containsOnly(caxiasId);
    }

    @Test
    @Order(23)
    @DisplayName("proximidade combina com os filtros que ja existiam (?neighborhood=)")
    void combinaComOutroFiltro() throws Exception {
        // A distancia vale para QUALQUER combinacao de filtros — foi por isso que o roteamento saiu
        // do controller e virou uma porta so no service. Todas as lojas da suite ficam no bairro
        // "Penha", entao o filtro devolve as cinco e a ordenacao por distancia tem de continuar
        // valendo por cima dele.
        List<Map<String, Object>> lista = buscar("&neighborhood=Penha" + geo(PENHA_LAT, PENHA_LNG));
        List<String> ids = idsDoTeste(lista);

        assertThat(ids).contains(penhaId, caxiasId, niteroiId, semCoordenadaId);
        assertThat(ids).containsSubsequence(caxiasId, niteroiId, semCoordenadaId);
        assertThat(porId(lista, semCoordenadaId).get("distance_km")).isNull();
    }

    // ─────────────────────────────────────────────────────────────
    // Validacao da entrada
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("[ERRO] meia coordenada (so lat) -> 422 ADDRESS_INVALID_COORDINATES")
    void meiaCoordenada() throws Exception {
        mvc.perform(get("/establishment?includeAll=true&lat=" + PENHA_LAT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(31)
    @DisplayName("[ERRO] ilha nula (0,0) -> 422")
    void ilhaNula() throws Exception {
        mvc.perform(get("/establishment?includeAll=true&lat=0&lng=0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(32)
    @DisplayName("[ERRO] fora do globo (lat 91) -> 422")
    void foraDoGlobo() throws Exception {
        mvc.perform(get("/establishment?includeAll=true&lat=91&lng=-43.27"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
    }

    @Test
    @Order(33)
    @DisplayName("[ERRO] radiusKm zero ou negativo -> 422 (devolveria lista vazia sempre)")
    void raioNaoPositivo() throws Exception {
        for (String raio : List.of("0", "-5")) {
            mvc.perform(get("/establishment?includeAll=true" + geo(PENHA_LAT, PENHA_LNG) + "&radiusKm=" + raio))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.code").value("ADDRESS_INVALID_COORDINATES"));
        }
    }

    @Test
    @Order(34)
    @DisplayName("a vitrine por proximidade e ANONIMA — sem token, 200")
    void semToken() throws Exception {
        // A lista de perto e a isca do cadastro, nao o premio dele.
        mvc.perform(get("/establishment?includeAll=true" + geo(PENHA_LAT, PENHA_LNG)))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────

    private record Loja(String id, String nome) {}

    /** Cria dono + loja e, se vier coordenada, o endereco com ela. */
    private Loja criarLoja(Double lat, Double lng) throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());

        var address = new AddressRequest(
                faker.address().streetName(), "100", null, "Penha",
                "Rio de Janeiro", "RJ", "21012000", null, lat, lng);

        mvc.perform(post("/establishments/" + estab.id() + "/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", owner.token())
                        .content(mapper.writeValueAsString(address)))
                .andExpect(status().isCreated());

        return new Loja(estab.id(), estab.name());
    }

    private String geo(double lat, double lng) {
        return "&lat=" + lat + "&lng=" + lng;
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
        List<String> meus = List.of(penhaId, penhaGemeaId, caxiasId, niteroiId, semCoordenadaId);
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
