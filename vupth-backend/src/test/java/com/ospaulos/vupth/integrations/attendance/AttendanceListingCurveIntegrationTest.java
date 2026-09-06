package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — <b>a curva de {@code GET /attendances}</b> (a agenda do Partner).
 *
 * <p>O endereco da lentidao aqui era diferente do B10 e chegava ao mesmo lugar: a consulta era
 * <b>nativa</b>, e consulta nativa <b>nao hidrata associacao</b>. Os {@code LEFT JOIN} que ela tinha
 * serviam ao filtro, nunca ao mapeamento, e cada atendimento voltava com {@code client},
 * {@code professional}, {@code establishment} e {@code serviceItems} preguicosos. O service
 * compensava com um {@code Hibernate.initialize(...)} por atendimento — <b>ate 5 idas ao banco por
 * linha</b>. Um dia de 40 atendimentos custava ~200 consultas.</p>
 *
 * <p>Como no B10, o que se afirma aqui e o que <b>causava</b> o tempo — o numero de idas ao banco —
 * e nao o tempo em si, que varia com a maquina e passaria verde num CI ocioso mesmo com o N+1 de
 * volta. O segundo teste guarda o contrato: os campos que o app le continuam preenchidos, e sao
 * exatamente eles que vinham daquelas associacoes preguicosas.</p>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttendanceListingCurveIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired EntityManagerFactory entityManagerFactory;

    private final Faker faker = new Faker();

    private String professionalToken;
    private UUID professionalId;
    private UUID establishmentId;
    private UUID serviceItemId;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.professionalId = UUID.fromString(owner.id());

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token());
        this.establishmentId = UUID.fromString(estab.id());

        TestUserUtils.becomeProfessional(mvc, mapper, faker, owner.token(), estab.id(), "Prof da Agenda");
        // Relogin: o papel PROFESSIONAL so entra no token novo (quem autoriza e o banco, mas o
        // filtro le o token).
        this.professionalToken = relogin(owner.email(), owner.password());

        var service = TestUserUtils.createServiceItem(mvc, mapper, estab.id(), owner.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemId = UUID.fromString(service.id());

        TestUserUtils.openEstablishment(establishmentRepository, estab.id());

        log.info("[CurvaAtendimentos] setup — establishment={} professional={}",
                establishmentId, professionalId);
    }

    private String relogin(String email, String password) throws Exception {
        MvcResult login = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + new JSONObject(login.getResponse().getContentAsString())
                .getJSONObject("tokens").getString("access_token");
    }

    /** Coloca mais um cliente na fila do profissional. */
    private void enqueueOneClient() throws Exception {
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(
                professionalId, establishmentId, UUID.fromString(client.id()),
                List.of(serviceItemId),
                AttendanceStatus.SCHEDULED, OffsetDateTime.now().plusMinutes(30), 30,
                OffsetDateTime.now());
        mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private JSONArray listarAgenda() throws Exception {
        MvcResult res = mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId.toString())
                        .header("Authorization", professionalToken))
                .andExpect(status().isOk())
                .andReturn();
        return new JSONArray(res.getResponse().getContentAsString());
    }

    @Test
    @Order(10)
    @DisplayName("o custo de GET /attendances NAO cresce com o tamanho do dia")
    void queryCountDoesNotGrowWithTheNumberOfAttendances() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        enqueueOneClient();

        // Aquece: a primeira execucao paga preparacao que nao se repete.
        assertThat(listarAgenda().length()).isEqualTo(1);

        stats.clear();
        assertThat(listarAgenda().length()).isEqualTo(1);
        long comUm = stats.getPrepareStatementCount();

        enqueueOneClient();
        enqueueOneClient();
        enqueueOneClient();

        stats.clear();
        assertThat(listarAgenda().length()).isEqualTo(4);
        long comQuatro = stats.getPrepareStatementCount();

        log.info("[CurvaAtendimentos] statements — 1 atendimento={} · 4 atendimentos={}",
                comUm, comQuatro);

        // Antes desta mudanca cada atendimento a mais custava ate 5 idas ao banco (loja, profissional,
        // o baseUser dele, os servicos e o baseUser do cliente): tres a mais custavam ~15 statements.
        //
        // ⚠️ O limite abaixo NAO e 0 por um motivo que nao pertence a esta consulta: sobraram 2 por
        // atendimento, e elas vem do `@OneToOne(mappedBy=..., fetch=LAZY)` de BaseUser para owner /
        // professional / client. LAZY no lado INVERSO de um one-to-one nao existe sem bytecode
        // enhancement — o Hibernate precisa consultar para saber se e null. Como cada atendimento
        // traz um cliente diferente, cada linha carrega um BaseUser novo e paga o pedagio. E defeito
        // GLOBAL (todo BaseUser do sistema paga), registrado a parte.
        //
        // Quando aquele item fechar, este limite vira 2 — constante, independente do numero de
        // linhas. Ate la ele ainda guarda o que importa aqui: se alguem reintroduzir o laco de
        // Hibernate.initialize por atendimento, a conta volta para ~5/linha e o teste cai.
        assertThat(comQuatro - comUm)
                .as("tres atendimentos a mais podem custar no maximo o pedagio conhecido do BaseUser")
                .isLessThanOrEqualTo(3 * 2);
    }

    @Test
    @Order(20)
    @DisplayName("a lista continua trazendo os campos que vinham das associacoes preguicosas")
    void listingStillCarriesTheAssociatedFields() throws Exception {
        JSONObject primeiro = listarAgenda().getJSONObject(0);
        log.info("[CurvaAtendimentos] item — {}", primeiro);

        // Cada uma destas linhas era, antes, um Hibernate.initialize() por atendimento.
        assertThat(primeiro.isNull("clientId"))
                .as("cliente do atendimento — vinha da associacao client")
                .isFalse();
        assertThat(primeiro.optString("professionalName", null))
                .as("nome do profissional — vinha de professional.baseUser")
                .isNotBlank();
        assertThat(primeiro.isNull("establishmentId"))
                .as("id da loja — vinha da associacao establishment")
                .isFalse();
        assertThat(primeiro.getJSONArray("services").length())
                .as("servicos do atendimento — vinham da colecao serviceItems")
                .isGreaterThan(0);
    }

    @Test
    @Order(30)
    @DisplayName("os filtros nulos continuam significando 'sem filtro' (o que o CAST da query nativa fazia)")
    void nullFiltersStillMeanNoFilter() throws Exception {
        // Sem establishmentId: o service resolve a loja pelo profissional do token.
        MvcResult semLoja = mvc.perform(get("/attendances")
                        .header("Authorization", professionalToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new JSONArray(semLoja.getResponse().getContentAsString()).length())
                .as("sem filtro nenhum a agenda do dia sai inteira")
                .isEqualTo(4);

        // Com status: filtra de verdade, e o enum viaja tipado (nao mais como String).
        MvcResult finalizados = mvc.perform(get("/attendances")
                        .param("establishmentId", establishmentId.toString())
                        .param("status", AttendanceStatus.FINISHED.name())
                        .header("Authorization", professionalToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(new JSONArray(finalizados.getResponse().getContentAsString()).length())
                .as("ninguem foi finalizado ainda")
                .isZero();
    }
}
