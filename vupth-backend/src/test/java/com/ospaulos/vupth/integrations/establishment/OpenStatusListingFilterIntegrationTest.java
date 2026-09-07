package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — a vitrine publica: quem aparece, em que ordem, e os filtros de prontidao.
 *
 * <h2>O que mudou em 06/09 (decisao do Andre)</h2>
 * Ate aqui esta classe afirmava o contrario do que afirma agora: o {@code GET /establishment}
 * devolvia <b>so</b> loja {@code OPEN} e o {@code ?includeAll=true} era como se pedia o resto.
 * <b>Nao era o que ninguem queria</b> — o Front-Client mandava {@code includeAll=true} na Home
 * ("o cliente pode querer agendar para a reabertura") e o Front-Partner mandava na busca de
 * afiliacao (loja recem-criada nasce {@code CLOSED}). O corte so pegava quem <b>esquecesse</b> o
 * parametro.
 *
 * <p>Entao: o default devolve <b>tudo</b>, {@code includeAll} fica aceito e inocuo, a loja fechada
 * nao disputa o topo (aberta primeiro, sempre) e quem quer a vitrine antiga pede
 * {@code ?openNow=true} — que e <b>melhor</b> que o corte antigo, porque olha o {@code is_open}
 * efetivo e nao o {@code open_status} cru.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenStatusListingFilterIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;

    private final Faker faker = new Faker();

    /** ABERTA, com profissional-com-servico: espera nao-nula, available_professionals == 1. */
    String openId;
    /** FECHADA (nasce assim), com profissional-com-servico: e a loja que o cliente quer AGENDAR. */
    String closedId;
    /** ABERTA e VAZIA: sem profissional, entao espera null e available_professionals == 0. */
    String openEmptyId;

    /** Prefixos unicos: o banco de teste e compartilhado, e a ordem alfabetica precisa de ancora. */
    private String nomeZ;
    private String nomeA;

    @BeforeAll
    void setup() throws Exception {
        String marca = UUID.randomUUID().toString().substring(0, 8);
        this.nomeZ = "ZZZ Vitrine " + marca;
        this.nomeA = "AAA Vitrine " + marca;

        var owner1 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var aberta = TestUserUtils.becomeOwner(mvc, mapper, faker, owner1.token());
        this.openId = aberta.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner1.token(), openId, "Prof Aberta");
        TestUserUtils.createServiceItem(mvc, mapper, openId, owner1.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        rename(openId, nomeZ);
        forceOpen(openId);

        var owner2 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var fechada = TestUserUtils.becomeOwner(mvc, mapper, faker, owner2.token());
        this.closedId = fechada.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner2.token(), closedId, "Prof Fechada");
        TestUserUtils.createServiceItem(mvc, mapper, closedId, owner2.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));

        var owner3 = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        var vazia = TestUserUtils.becomeOwner(mvc, mapper, faker, owner3.token());
        this.openEmptyId = vazia.id();
        rename(openEmptyId, nomeA);
        forceOpen(openEmptyId);

        log.info("[Vitrine] setup — open={} closed={} openEmpty={}", openId, closedId, openEmptyId);
    }

    // ------------------------------------------------------------------ quem aparece

    @Test
    @Order(10)
    @DisplayName("GET /establishment — o default devolve ABERTA e FECHADA (a fechada nao some mais)")
    void defaultListing_returnsOpenAndClosed() throws Exception {
        List<String> ids = idsOf("/establishment");

        assertThat(ids).as("a aberta continua na vitrine").contains(openId);
        assertThat(ids)
                .as("a fechada aparece SEM parametro nenhum — quem quer agendar precisa acha-la")
                .contains(closedId);
    }

    @Test
    @Order(20)
    @DisplayName("?includeAll=true — continua valendo, e agora nao faz diferenca nenhuma")
    void includeAll_isNoOp() throws Exception {
        assertThat(idsOf("/establishment?includeAll=true"))
                .as("os dois apps em campo mandam este parametro; ele nao pode quebrar nem mudar nada")
                .containsAll(List.of(openId, closedId));
    }

    @Test
    @Order(30)
    @DisplayName("?includeAll=false — o corte NAO volta: a fechada continua na lista")
    void includeAllFalse_stillReturnsClosed() throws Exception {
        assertThat(idsOf("/establishment?includeAll=false"))
                .as("era aqui que a loja fechada sumia — hoje quem tira e o openNow")
                .contains(closedId);
    }

    // ------------------------------------------------------------------ ordem

    @Test
    @Order(40)
    @DisplayName("a ABERTA vem antes da FECHADA — espera 0 de loja fechada nao lidera a Home")
    void openComesBeforeClosed() throws Exception {
        List<String> ids = idsOf("/establishment");

        assertThat(ids.indexOf(openId))
                .as("aberta antes de fechada, sempre — e o primeiro criterio da ordenacao")
                .isLessThan(ids.indexOf(closedId));
    }

    @Test
    @Order(50)
    @DisplayName("?sort=name — ordem alfabetica DENTRO do bloco das abertas")
    void sortByName_ordersAlphabeticallyAmongOpen() throws Exception {
        List<String> ids = idsOf("/establishment?sort=name");

        assertThat(ids.indexOf(openEmptyId))
                .as("'" + nomeA + "' antes de '" + nomeZ + "' — as duas estao ABERTAS")
                .isLessThan(ids.indexOf(openId));
        assertThat(ids.indexOf(openId))
                .as("e a fechada continua depois das abertas, mesmo com sort explicito")
                .isLessThan(ids.indexOf(closedId));
    }

    @Test
    @Order(60)
    @DisplayName("?sort=distance SEM lat/lng — cai no wait sem erro (quem negou o GPS nao fica sem lista)")
    void sortByDistanceWithoutCoordinates_fallsBack() throws Exception {
        assertThat(idsOf("/establishment?sort=distance"))
                .as("200 com a lista de sempre, nao 422")
                .contains(openId);
    }

    // ------------------------------------------------------------------ filtros de prontidao

    @Test
    @Order(70)
    @DisplayName("?openNow=true — repoe a vitrine antiga como ESCOLHA: a fechada sai")
    void openNow_excludesClosed() throws Exception {
        List<String> ids = idsOf("/establishment?openNow=true");

        assertThat(ids).contains(openId);
        assertThat(ids).as("aqui, sim, a fechada sai — porque o usuario pediu").doesNotContain(closedId);
    }

    @Test
    @Order(80)
    @DisplayName("?hasAvailability=true — loja sem profissional-com-servico sai")
    void hasAvailability_excludesEmptyShop() throws Exception {
        List<String> ids = idsOf("/establishment?hasAvailability=true");

        assertThat(ids).as("tem profissional com servico").contains(openId);
        assertThat(ids)
                .as("aberta, mas sem ninguem para atender — e o corte que o Front-Client fazia no app")
                .doesNotContain(openEmptyId);
    }

    @Test
    @Order(90)
    @DisplayName("?maxWaitMinutes — espera NULL sai junto: 'desconhecida' nao cabe em 'ate N minutos'")
    void maxWaitMinutes_excludesUnknownWait() throws Exception {
        List<String> ids = idsOf("/establishment?maxWaitMinutes=1000");

        assertThat(ids).as("fila vazia, espera bem abaixo do teto").contains(openId);
        assertThat(ids).as("estimated_wait_minutes null — sem profissional com servico")
                .doesNotContain(openEmptyId);
    }

    @Test
    @Order(100)
    @DisplayName("filtros COMBINAM entre si (openNow + hasAvailability)")
    void filtersCombine() throws Exception {
        List<String> ids = idsOf("/establishment?openNow=true&hasAvailability=true");

        assertThat(ids).contains(openId);
        assertThat(ids).doesNotContain(closedId, openEmptyId);
    }

    // ------------------------------------------------------------------ o que e recusado

    @Test
    @Order(110)
    @DisplayName("?sort desconhecido — 422 ESTABLISHMENT_INVALID_FILTER (nao ordena de outro jeito calado)")
    void unknownSort_isRejected() throws Exception {
        mvc.perform(get("/establishment").param("sort", "melhores"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_INVALID_FILTER"));
    }

    @Test
    @Order(120)
    @DisplayName("?maxWaitMinutes negativo — 422; o ZERO passa ('so quem atende na hora')")
    void negativeMaxWait_isRejected_butZeroIsValid() throws Exception {
        mvc.perform(get("/establishment").param("maxWaitMinutes", "-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ESTABLISHMENT_INVALID_FILTER"));

        mvc.perform(get("/establishment").param("maxWaitMinutes", "0"))
                .andExpect(status().isOk());
    }

    @Test
    @Order(130)
    @DisplayName("?sort=WAIT em caixa alta — a caixa nao e erro de intencao")
    void sortIsCaseInsensitive() throws Exception {
        assertThat(idsOf("/establishment?sort=WAIT")).contains(openId);
    }

    // ------------------------------------------------------------------ utilitarios

    /** Os ids da vitrine, NA ORDEM em que o endpoint devolveu. */
    private List<String> idsOf(String url) throws Exception {
        String body = mvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Map<String, Object>> list = mapper.readValue(body, new TypeReference<>() {});
        return list.stream().map(m -> String.valueOf(m.get("id"))).toList();
    }

    /** Abre a loja direto no banco (pula o gate #31, que exigiria profissional com servico). */
    private void forceOpen(String establishmentId) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setOpenStatus(OpenStatus.OPEN);
        establishmentRepository.save(e);
    }

    /** Nome deterministico: o faker nao serve para afirmar ordem alfabetica. */
    private void rename(String establishmentId, String nome) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setName(nome);
        establishmentRepository.save(e);
    }
}
