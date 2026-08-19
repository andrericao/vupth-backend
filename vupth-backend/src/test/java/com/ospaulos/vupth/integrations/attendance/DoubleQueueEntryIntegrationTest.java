package com.ospaulos.vupth.integrations.attendance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import lombok.extern.slf4j.Slf4j;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — item #38: a invariante do DUPLO INGRESSO na fila.
 *
 * <p><b>O defeito.</b> Reproduzido em device pelo Andre (Client §4.2, 2026-08-07): estando
 * {@code IN_PROGRESS}, ele conseguiu entrar na fila do mesmo profissional outra vez e ficou
 * esperando o proprio corte terminar. O {@code createAttendance} tinha cinco portoes e nenhum
 * perguntava se aquele cliente ja estava ocupado. A guarda do app e local (le so o store do
 * aparelho), entao logout/login ou reinstalacao a furam — invariante de fila mora no servidor.
 *
 * <p><b>Nao e so contagem errada:</b> a fila conta o mesmo cliente duas vezes, a estimativa dos
 * outros infla, e o atendimento orfao <b>trava o fechamento da loja e o apagar-conta</b>, que
 * checam atendimento ativo.
 *
 * <p><b>⚠️ O RAIO E DECISAO REGISTRADA (Andre, 15/08, D1): bloqueia o par cliente x profissional,
 * e SO ele.</b> Os dois testes que fixam a decisao sao os que provam o que <b>continua passando</b>
 * — outro profissional da mesma loja, e um segundo cliente. Sem eles, alguem "corrige" o raio para
 * a loja inteira ou para o tipo de estabelecimento e nenhum teste reclama.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DoubleQueueEntryIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ServiceItemRepository serviceItemRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;

    private final Faker faker = new Faker();

    private String ownerToken, ownerProfessionalId, establishmentId;
    private List<UUID> ownerServiceItemIds;

    /** Segundo profissional da MESMA loja — existe para provar o limite do raio. */
    private TestUserUtils.UserAuthAndToken employee;
    private List<UUID> employeeServiceItemIds;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.ownerProfessionalId = owner.id();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        // Walk-in do cliente so entra com a loja aberta agora (gate BUG5 C1).
        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Owner Professional");
        limpaRefeicoes(ownerProfessionalId);

        var s1 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.ownerServiceItemIds = List.of(UUID.fromString(s1.id()));

        // Funcionario da mesma loja, com servico PROPRIO — o ServiceItem tem dono
        // (assertServiceItemsBelongToProfessional), entao o item do dono nao serve na fila dele.
        this.employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employee.token(), establishmentId, "Funcionario");
        limpaRefeicoes(employee.id());
        var s2 = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, employee.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte do funcionario", BigDecimal.valueOf(30));
        this.employeeServiceItemIds = List.of(UUID.fromString(s2.id()));

        log.info("[DoubleQueueEntry] setup — estab={} dono={} funcionario={}",
                establishmentId, ownerProfessionalId, employee.id());
    }

    /**
     * ⚠️ Libera a CADEIRA do profissional entre um teste e outro.
     *
     * <p>A classe reusa um unico profissional, e o app tem uma invariante que <b>ja existia</b>:
     * {@code startAttendance} recusa com <b>409 {@code ATTENDANCE_ALREADY_IN_PROGRESS}</b> quando o
     * profissional ja tem atendimento {@code IN_PROGRESS} <b>ou {@code PAUSED}</b>
     * ({@code Professional.getCurrentAttendance}). Como cada teste deixa o seu atendimento vivo de
     * proposito (e o estado que o #38 tem de barrar), sem esta limpeza o teste seguinte falha no
     * {@code /start} — por um motivo que <b>nao tem nada a ver com o duplo ingresso</b>.
     *
     * <p>Foi exatamente isso que a suite completa mostrou em 17/08: dois vermelhos apontando para
     * {@code patchOk}, e a leitura apressada era "pause e finish quebraram". O 409 vinha do
     * {@code /start} da linha de cima, com o assento ainda ocupado pelo teste anterior.
     *
     * <p>Fecha pelo repositorio, nao pela API: {@code PATCH /finish} exige {@code IN_PROGRESS} e nao
     * aceita {@code PAUSED} — limpar pela porta da frente exigiria {@code resume} antes, o que poria
     * regra de negocio dentro do cleanup. Isto aqui e arrumacao de estado, nao exercicio de fluxo.
     */
    @AfterEach
    void liberaACadeiraDoProfissional() {
        var ocupando = attendanceRepository.findByProfessional_IdAndStatusInOrderByScheduledTimeAsc(
                UUID.fromString(ownerProfessionalId),
                List.of(AttendanceStatus.IN_PROGRESS, AttendanceStatus.PAUSED));
        ocupando.forEach(a -> a.setStatus(AttendanceStatus.FINISHED));
        attendanceRepository.saveAll(ocupando);
    }

    /** Sem as refeicoes seedadas o ETA fica puramente o tempo de servico (ver QueueIntentIntegrationTest). */
    private void limpaRefeicoes(String professionalId) {
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(professionalId)));
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private MvcResult entrarNaFila(TestUserUtils.UserAuthAndToken cliente,
                                   String professionalId, List<UUID> itens) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(cliente.id()),
                itens,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(),
                30,
                OffsetDateTime.now(),
                null);
        return mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", cliente.token())
                        .content(mapper.writeValueAsString(request)))
                .andReturn();
    }

    /** Entra na fila do dono esperando 201 e devolve o id do atendimento. */
    private String entrarNaFilaDoDono(TestUserUtils.UserAuthAndToken cliente) throws Exception {
        MvcResult r = entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds);
        assertThat(r.getResponse().getStatus()).as("primeira entrada na fila").isEqualTo(201);
        return new JSONObject(r.getResponse().getContentAsString()).getString("id");
    }

    private TestUserUtils.UserAuthAndToken novoCliente() throws Exception {
        return TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
    }

    private void assertBloqueado(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as("segunda entrada tem de ser recusada").isEqualTo(409);
        JSONObject corpo = new JSONObject(r.getResponse().getContentAsString());
        assertThat(corpo.getString("code")).isEqualTo("CLIENT_ALREADY_IN_QUEUE");
        // A mensagem diz o que fazer para destravar — bloqueio sem saida reprova na loja de apps.
        assertThat(corpo.getString("message")).contains("Conclua ou cancele");
    }

    /** Nome proprio (nao "patch") para nao colidir com o {@code patch} do MockMvcRequestBuilders. */
    private void patchOk(String path, String id, String token) throws Exception {
        mvc.perform(patch(path, id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────
    // O que BLOQUEIA
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("SCHEDULED — aguardando na fila, nao entra de novo no mesmo profissional")
    void aguardando_naoEntraDeNovo() throws Exception {
        var cliente = novoCliente();
        entrarNaFilaDoDono(cliente);

        assertBloqueado(entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds));
    }

    @Test
    @Order(2)
    @DisplayName("IN_PROGRESS — o caso EXATO do device: sendo atendido, nao entra de novo")
    void emAtendimento_naoEntraDeNovo() throws Exception {
        var cliente = novoCliente();
        String id = entrarNaFilaDoDono(cliente);
        patchOk("/attendances/{id}/start", id, ownerToken);

        assertBloqueado(entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds));
    }

    @Test
    @Order(3)
    @DisplayName("PAUSED — atendimento pausado ainda ocupa a vaga")
    void pausado_naoEntraDeNovo() throws Exception {
        var cliente = novoCliente();
        String id = entrarNaFilaDoDono(cliente);
        patchOk("/attendances/{id}/start", id, ownerToken);
        patchOk("/attendances/{id}/pause", id, ownerToken);

        assertBloqueado(entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds));
    }

    @Test
    @Order(4)
    @DisplayName("LATE — atrasado continua na fila, entao continua ocupado")
    void atrasado_naoEntraDeNovo() throws Exception {
        var cliente = novoCliente();
        String id = entrarNaFilaDoDono(cliente);
        patchOk("/attendances/{id}/late", id, ownerToken);

        assertThat(attendanceRepository.findById(UUID.fromString(id)).orElseThrow().getStatus())
                .as("pre-condicao do teste")
                .isEqualTo(AttendanceStatus.LATE);
        assertBloqueado(entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds));
    }

    // ─────────────────────────────────────────────────────────────
    // O que CONTINUA PASSANDO — aqui mora a decisao D1
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("⚠️ D1 — na fila do dono, o MESMO cliente ainda entra na do funcionario (raio estreito)")
    void mesmaLojaOutroProfissional_continuaEntrando() throws Exception {
        var cliente = novoCliente();
        entrarNaFilaDoDono(cliente);

        MvcResult r = entrarNaFila(cliente, employee.id(), employeeServiceItemIds);

        assertThat(r.getResponse().getStatus())
                .as("o raio e cliente x PROFISSIONAL; alargar para a loja inteira e decisao do D1, nao efeito colateral")
                .isEqualTo(201);
    }

    @Test
    @Order(6)
    @DisplayName("outro cliente entra normalmente — o bloqueio e do individuo, nao da vaga")
    void outroCliente_entraNormalmente() throws Exception {
        var primeiro = novoCliente();
        entrarNaFilaDoDono(primeiro);

        var segundo = novoCliente();
        MvcResult r = entrarNaFila(segundo, ownerProfessionalId, ownerServiceItemIds);

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
    }

    // ─────────────────────────────────────────────────────────────
    // O bloqueio LIBERA — senao seria banimento, nao invariante
    // ─────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("depois de FINISHED o cliente entra de novo — o bloqueio nao pode ser permanente")
    void aposFinalizar_entraDeNovo() throws Exception {
        var cliente = novoCliente();
        String id = entrarNaFilaDoDono(cliente);
        patchOk("/attendances/{id}/start", id, ownerToken);
        patchOk("/attendances/{id}/finish", id, ownerToken);

        MvcResult r = entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds);

        assertThat(r.getResponse().getStatus())
                .as("terminou o corte, pode voltar a fila — sem isto o cliente ficaria trancado para sempre")
                .isEqualTo(201);
    }

    @Test
    @Order(8)
    @DisplayName("depois de CANCELLED o cliente entra de novo")
    void aposCancelar_entraDeNovo() throws Exception {
        var cliente = novoCliente();
        String id = entrarNaFilaDoDono(cliente);

        mvc.perform(put("/attendances/{id}/cancel", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", cliente.token()))
                .andExpect(status().isOk());

        MvcResult r = entrarNaFila(cliente, ownerProfessionalId, ownerServiceItemIds);

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
    }
}
