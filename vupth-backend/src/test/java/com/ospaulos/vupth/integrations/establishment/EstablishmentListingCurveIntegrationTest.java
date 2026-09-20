package com.ospaulos.vupth.integrations.establishment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — <b>a curva da listagem publica (B10)</b>.
 *
 * <p>O defeito nao era custo de partida: {@code GET /establishment} chamava o use case de
 * <b>detalhe</b> uma vez por loja, entao o custo crescia com o numero de lojas — ~81 ms/loja no
 * Render e ~740 ms/loja em producao, que punham a primeira tela do app em ~24s. Medir tempo em
 * teste seria instavel; o que se afirma aqui e o que causava o tempo: <b>quantas idas ao banco a
 * listagem faz</b>, e que esse numero NAO cresce com o tamanho da lista.</p>
 *
 * <p>O segundo teste guarda a outra metade da mudanca: a listagem passou a calcular a estimativa
 * por um caminho proprio, e o numero tem de continuar <b>identico</b> ao da tela de detalhe. Se
 * alguem otimizar a conta so de um lado, a mesma loja passa a mostrar dois tempos diferentes em
 * duas telas — e o tempo estimado e o produto.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EstablishmentListingCurveIntegrationTest {

    /** Prefixo comum dos nomes: permite pedir "as tres" ou "so uma" pelo mesmo endpoint. */
    private static final String PREFIX = "CurvaB10";

    @Autowired MockMvc mvc;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private final Faker faker = new Faker();

    private final List<String> establishmentIds = new ArrayList<>();
    private final List<String> establishmentNames = new ArrayList<>();

    @BeforeAll
    void setup() throws Exception {
        for (int i = 1; i <= 3; i++) {
            var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
            String id = estab.id();

            TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), id, "Prof " + i);
            // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
            // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
            TestUserUtils.startShift(professionalRepository, id);
            var service = TestUserUtils.createServiceItem(mvc, mapper, id, owner.token(),
                    ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
            TestUserUtils.openEstablishment(establishmentRepository, id);

            String name = rename(id, PREFIX + " Loja " + i);
            establishmentIds.add(id);
            establishmentNames.add(name);

            // Um cliente na fila da PRIMEIRA loja: com fila vazia em todas, "os dois numeros batem"
            // seria 0 == 0 e nao provaria nada.
            if (i == 1) {
                var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
                AttendanceRequest request = new AttendanceRequest(
                        UUID.fromString(owner.id()), UUID.fromString(id), UUID.fromString(client.id()),
                        List.of(UUID.fromString(service.id())),
                        AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(30), 30,
                        OffsetDateTime.now());
                mvc.perform(post("/attendances")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", client.token())
                                .content(mapper.writeValueAsString(request)))
                        .andExpect(status().isCreated());
            }
        }
        log.info("[CurvaB10] setup — lojas={}", establishmentIds);
    }

    /** Renomeia direto no banco: o filtro {@code ?name=} e o que isola 1 loja de 3 no mesmo teste. */
    private String rename(String establishmentId, String name) {
        Establishment e = establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow();
        e.setName(name);
        establishmentRepository.save(e);
        return name;
    }

    private JSONArray listar(String name) throws Exception {
        MvcResult res = mvc.perform(get("/establishment").param("name", name))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONArray(res.getResponse().getContentAsString());
    }

    @Test
    @Order(10)
    @DisplayName("o custo da listagem NAO cresce com o numero de lojas (o N+1 do B10 nao volta)")
    void queryCountDoesNotGrowWithTheNumberOfEstablishments() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        // Aquece: a primeira execucao paga metadata/preparacao que nao se repete.
        assertThat(listar(PREFIX).length()).isEqualTo(3);

        stats.clear();
        assertThat(listar(establishmentNames.get(0)).length()).isEqualTo(1);
        long comUmaLoja = stats.getPrepareStatementCount();

        stats.clear();
        assertThat(listar(PREFIX).length()).isEqualTo(3);
        long comTresLojas = stats.getPrepareStatementCount();

        log.info("[CurvaB10] statements — 1 loja={} · 3 lojas={}", comUmaLoja, comTresLojas);

        // Antes da correcao cada loja extra custava >= 4 idas ao banco (a loja, os profissionais e
        // duas por profissional, em transacao propria). O lote nao paga por loja: as consultas sao
        // as mesmas, so trazem mais linhas. A folga de 2 cobre lazy loads em lote (endereco).
        assertThat(comTresLojas - comUmaLoja)
                .as("duas lojas a mais nao podem custar idas ao banco proporcionais")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @Order(20)
    @DisplayName("o tempo estimado da listagem e o mesmo do detalhe — fonte unica do numero")
    void listingEtaMatchesDetailEta() throws Exception {
        String establishmentId = establishmentIds.get(0);

        JSONObject naListagem = listar(establishmentNames.get(0)).getJSONObject(0);
        int listagem = naListagem.getInt("estimated_wait_minutes");

        MvcResult res = mvc.perform(get("/establishment/id/{id}/professionals-with-queue", establishmentId))
                .andExpect(status().isOk())
                .andReturn();
        JSONArray professionals = new JSONObject(res.getResponse().getContentAsString())
                .getJSONArray("professionals");

        int detalhe = Integer.MAX_VALUE;
        for (int i = 0; i < professionals.length(); i++) {
            detalhe = Math.min(detalhe, professionals.getJSONObject(i).getInt("estimated_wait_minutes"));
        }

        assertThat(listagem)
                .as("a vitrine e a tela da loja mostram o mesmo tempo (QueueUtils.professionalEta)")
                .isEqualTo(detalhe);
        assertThat(listagem)
                .as("com um cliente de 30min na fila, o numero nao pode ser zero")
                .isGreaterThan(0);
        assertThat(naListagem.getInt("available_professionals"))
                .as("a contagem de profissionais com servico continua vindo do mesmo conjunto")
                .isEqualTo(professionals.length());
    }
}
