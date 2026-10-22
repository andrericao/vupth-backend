package com.ospaulos.vupth.integrations.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.api.user.dto.request.auth.LoginRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.DeleteAccountRequest;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.establishment.model.Establishment;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.establishment.valueObject.EstablishmentStatus;
import com.ospaulos.vupth.domain.establishment.valueObject.OpenStatus;
import com.ospaulos.vupth.domain.notification.repository.DeviceTokenRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests — apagar a conta (Partner S19 / LGPD / exigencia das lojas de app).
 *
 * <p>A invariante central que estes testes protegem: <b>apaga-se o titular, nao as relacoes
 * dele</b>. Um atendimento tem tres envolvidos (cliente, profissional, estabelecimento) e cada
 * um continua com direito ao proprio historico — entao o atendimento SOBREVIVE a exclusao do
 * cliente, agora atribuido a "Usuario removido".</p>
 *
 * Cobre:
 * <ul>
 *   <li>422 sem {@code current_password} · 401 senha errada · 401 sem token</li>
 *   <li>409 ACCOUNT_DELETE_NOT_ALLOWED — os TRES portoes, cada um pela sua MENSAGEM: cliente na
 *       fila · profissional com atendimento ativo · dono cuja loja tem fila viva. A mensagem
 *       importa porque os tres devolvem o mesmo {@code code}: asserir so o code deixaria passar
 *       um portao disparando no lugar do outro (foi o que aconteceu — o dono e tambem o
 *       profissional que atende, entao o portao do PROFISSIONAL respondia pelos dois)</li>
 *   <li>204 + anonimizacao do {@code base_user} (nome/e-mail/telefone/senha) e {@code deleted_at}</li>
 *   <li>o ATENDIMENTO do cliente apagado continua existindo (historico do profissional)</li>
 *   <li>o e-mail original volta a ficar livre para um cadastro novo</li>
 *   <li>login e access token vivo param de funcionar</li>
 *   <li>dono apagado: a loja e desligada (CLOSED), sem dono, e os profissionais sao desafiliados</li>
 * </ul>
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountDeletionIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository userRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;
    @Autowired ProfessionalIntervalRepository professionalIntervalRepository;
    @Autowired DeviceTokenRepository deviceTokenRepository;

    private final Faker faker = new Faker();

    String ownerToken, ownerId, ownerEmail, ownerPassword, establishmentId;
    List<UUID> serviceItemIds;

    TestUserUtils.UserAuthAndToken client;
    UUID clientAttendanceId;

    /**
     * Segundo profissional da MESMA loja, e um cliente na fila DELE. E o unico jeito de exercitar
     * o portao do estabelecimento isolado: enquanto quem atende for o proprio dono, o portao do
     * profissional dispara antes e o do dono nunca e alcancado.
     */
    TestUserUtils.UserAuthAndToken employee;
    List<UUID> employeeServiceItemIds;
    TestUserUtils.UserAuthAndToken otherClient;

    @BeforeAll
    void setup() throws Exception {
        var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.ownerToken = owner.token();
        this.ownerId = owner.id();
        this.ownerEmail = owner.email();
        this.ownerPassword = owner.password();

        var estab = TestUserUtils.becomeOwner(mvc, mapper, faker, ownerToken);
        this.establishmentId = estab.id();
        TestUserUtils.becomeProfessional(mvc, mapper, faker, ownerToken, establishmentId, "Owner Professional");

        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(ownerId)));

        var service = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, ownerToken,
                ServiceItemType.CORTE_MAQUINA, "Corte", BigDecimal.valueOf(30));
        this.serviceItemIds = List.of(UUID.fromString(service.id()));

        TestUserUtils.openEstablishment(establishmentRepository, establishmentId);
        // 20/09: quem nao esta em expediente nao recebe fila, e o cliente entra na fila LOGO ABAIXO —
        // antes do funcionario existir. Por isso a chegada aparece duas vezes neste setup: aqui para o
        // dono-profissional, e de novo depois que o funcionario se filia.
        TestUserUtils.startShift(professionalRepository, establishmentId);

        this.client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        this.clientAttendanceId = enterQueue(client, ownerId, serviceItemIds);

        // Funcionario da mesma loja, com servico PROPRIO: o ServiceItem tem dono
        // (`assertServiceItemsBelongToProfessional`), entao o do dono nao serve para a fila dele.
        this.employee = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, employee.token(), establishmentId, "Funcionario");
        // 20/09: profissional nasce OFF e quem nao esta em expediente nao recebe fila.
        // Aqui o expediente e cenario, nao o objeto do teste — entao ele chega pelo atalho.
        TestUserUtils.startShift(professionalRepository, establishmentId);
        professionalIntervalRepository.deleteAll(
                professionalIntervalRepository.findByProfessional_Id(UUID.fromString(employee.id())));
        var employeeService = TestUserUtils.createServiceItem(mvc, mapper, establishmentId, employee.token(),
                ServiceItemType.CORTE_MAQUINA, "Corte do funcionario", BigDecimal.valueOf(30));
        this.employeeServiceItemIds = List.of(UUID.fromString(employeeService.id()));

        this.otherClient = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);

        log.info("[AccountDeletion] setup — estab={} owner={} client={} employee={}",
                establishmentId, ownerId, client.id(), employee.id());
    }

    private UUID enterQueue(TestUserUtils.UserAuthAndToken user,
                            String professionalId, List<UUID> items) throws Exception {
        AttendanceRequest request = new AttendanceRequest(
                UUID.fromString(professionalId),
                UUID.fromString(establishmentId),
                UUID.fromString(user.id()),
                items,
                AttendanceStatus.SCHEDULED,
                OffsetDateTime.now(),
                30,
                OffsetDateTime.now(),
                null);

        MvcResult result = mvc.perform(post("/attendances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", user.token())
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        return UUID.fromString(new JSONObject(result.getResponse().getContentAsString()).getString("id"));
    }

    private MvcResult deleteAccount(String token, String password) throws Exception {
        return mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", token)
                        .content(mapper.writeValueAsString(new DeleteAccountRequest(password))))
                .andReturn();
    }

    // ───────────────────────────────────────────
    // Confirmacao pela senha
    // ───────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("DELETE /profile/delete/me — 401 sem token")
    void delete_noToken() throws Exception {
        mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new DeleteAccountRequest("qualquer"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    @DisplayName("DELETE /profile/delete/me — 422 com corpo mas sem current_password")
    void delete_missingPassword() throws Exception {
        mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token())
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /**
     * ⚠️ Este e EXATAMENTE o que os apps fazem hoje: o botao "Deletar conta" chama a rota sem corpo
     * nenhum. Nao cai no {@code @Valid} (422) — morre antes, no Jackson, como
     * {@code HttpMessageNotReadableException} = <b>400 INVALID_REQUEST_BODY</b>. Achado pelo newman:
     * os cleanups das collections tomaram 400 onde a doc prometia 422.
     */
    @Test
    @Order(3)
    @DisplayName("DELETE /profile/delete/me — 400 SEM CORPO (o que o app de hoje recebe)")
    void delete_noBodyAtAll() throws Exception {
        mvc.perform(delete("/profile/delete/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    @Test
    @Order(4)
    @DisplayName("DELETE /profile/delete/me — 401 senha errada, e a conta continua viva")
    void delete_wrongPassword() throws Exception {
        MvcResult result = deleteAccount(client.token(), client.password() + "-errada");

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("CURRENT_PASSWORD_INVALID");

        BaseUser stillThere = userRepository.findById(UUID.fromString(client.id())).orElseThrow();
        assertThat(stillThere.isDeleted()).isFalse();
        assertThat(stillThere.getEmail()).isEqualTo(client.email());
    }

    // ───────────────────────────────────────────
    // Portao 409 — compromisso vivo com terceiros
    // ───────────────────────────────────────────

    @Test
    @Order(10)
    @DisplayName("DELETE /profile/delete/me — 409 cliente na fila (senha certa, mas ha compromisso)")
    void delete_blockedWhileClientInQueue() throws Exception {
        MvcResult result = deleteAccount(client.token(), client.password());

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString())
                .contains("ACCOUNT_DELETE_NOT_ALLOWED")
                .contains("ainda está em uma fila");
    }

    /**
     * O dono deste teste tambem ATENDE, e o cliente do setup esta na fila dele. Quem barra aqui e
     * o portao do PROFISSIONAL — nao o do dono, que so e alcancado no {@code Order(25)}, depois
     * que o profissional-dono fica sem atendimento proprio.
     */
    @Test
    @Order(11)
    @DisplayName("DELETE /profile/delete/me — 409 profissional com atendimento ativo")
    void delete_blockedWhileProfessionalHasActiveAttendances() throws Exception {
        MvcResult result = deleteAccount(ownerToken, ownerPassword);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString())
                .contains("ACCOUNT_DELETE_NOT_ALLOWED")
                .contains("atendimentos em andamento");
    }

    // ───────────────────────────────────────────
    // Caminho feliz do CLIENTE — e o que sobrevive
    // ───────────────────────────────────────────

    @Test
    @Order(20)
    @DisplayName("DELETE /profile/delete/me — 204 apos sair da fila; base_user anonimizado")
    void delete_clientSuccess() throws Exception {
        mvc.perform(delete("/attendances/" + clientAttendanceId + "/leave-queue")
                        .header("Authorization", client.token()))
                .andExpect(status().isOk());

        MvcResult result = deleteAccount(client.token(), client.password());
        assertThat(result.getResponse().getStatus()).isEqualTo(204);

        BaseUser deleted = userRepository.findById(UUID.fromString(client.id())).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getIsActive()).isFalse();
        assertThat(deleted.getFirstName()).isEqualTo(BaseUser.ANONYMIZED_FIRST_NAME);
        assertThat(deleted.getLastName()).isEqualTo(BaseUser.ANONYMIZED_LAST_NAME);
        assertThat(deleted.getEmail()).isNotEqualTo(client.email()).endsWith("@deleted.invalid");
        assertThat(deleted.getPhone()).isNull();
        assertThat(deleted.getBirthDate()).isNull();
        assertThat(deleted.getRoles()).isEmpty();
        assertThat(deviceTokenRepository.findByUserId(UUID.fromString(client.id()))).isEmpty();
    }

    @Test
    @Order(21)
    @DisplayName("O ATENDIMENTO do cliente apagado continua existindo — e o historico do profissional")
    void delete_keepsAttendanceHistory() {
        assertThat(attendanceRepository.findById(clientAttendanceId))
                .as("o atendimento pertence tambem ao profissional e ao estabelecimento")
                .isPresent();
    }

    @Test
    @Order(22)
    @DisplayName("O e-mail original volta a ficar livre para um cadastro novo")
    void delete_freesTheEmail() throws Exception {
        mvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new UserRegisterRequest(client.email(), "OutraSenha@123"))))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(23)
    @DisplayName("A conta apagada nao loga mais com a senha antiga")
    void delete_cannotLoginAnymore() throws Exception {
        MvcResult result = mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new LoginRequest(client.email(), client.password()))))
                .andReturn();

        // O e-mail antigo agora pertence ao cadastro novo do Order(22) — a senha e outra.
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @Order(24)
    @DisplayName("O access token que ja estava na mao do app morre no request seguinte")
    void delete_killsLiveAccessToken() throws Exception {
        mvc.perform(get("/profile/me").header("Authorization", client.token()))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    // ───────────────────────────────────────────
    // Portao do DONO — so alcancavel sem fila propria
    // ───────────────────────────────────────────

    /**
     * O terceiro portao, isolado: o dono nao tem mais atendimento COMO PROFISSIONAL (o cliente
     * saiu da fila no {@code Order(20)}), mas a loja dele tem cliente na fila de OUTRO
     * profissional. Apagar a conta agora deixaria a fila sem responsavel — dai o 409.
     *
     * <p>Ao final o cliente sai da fila, para o {@code Order(30)} poder apagar de verdade.</p>
     */
    @Test
    @Order(25)
    @DisplayName("DELETE /profile/delete/me — 409 dono cuja loja tem fila viva (com OUTRO profissional)")
    void delete_blockedWhileEstablishmentHasQueue() throws Exception {
        UUID otherAttendanceId = enterQueue(otherClient, employee.id(), employeeServiceItemIds);

        MvcResult result = deleteAccount(ownerToken, ownerPassword);

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(result.getResponse().getContentAsString())
                .contains("ACCOUNT_DELETE_NOT_ALLOWED")
                .as("a mensagem tem de ser a do ESTABELECIMENTO — se vier a do profissional, "
                        + "o portao do dono continua sem cobertura")
                .contains("A sua loja ainda tem clientes na fila");

        BaseUser stillThere = userRepository.findById(UUID.fromString(ownerId)).orElseThrow();
        assertThat(stillThere.isDeleted())
                .as("a recusa nao pode deixar a conta meio desmontada")
                .isFalse();
        assertThat(establishmentRepository.findById(UUID.fromString(establishmentId)).orElseThrow()
                .getOwner()).isNotNull();

        mvc.perform(delete("/attendances/" + otherAttendanceId + "/leave-queue")
                        .header("Authorization", otherClient.token()))
                .andExpect(status().isOk());
    }

    // ───────────────────────────────────────────
    // Caminho feliz do DONO — a loja e desligada
    // ───────────────────────────────────────────

    @Test
    @Order(30)
    @DisplayName("DELETE /profile/delete/me — dono com loja: 409 OWNER_MUST_TRANSFER; apaga a loja e ai 204")
    void delete_ownerSuccess() throws Exception {
        // desativar-conta (d): loja sem dono nao existe — com loja, o apagar da conta recusa.
        MvcResult refused = deleteAccount(ownerToken, ownerPassword);
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("OWNER_MUST_TRANSFER").contains("Apague a loja");

        mvc.perform(delete("/establishment/{id}", establishmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", ownerToken)
                        .content("{\"current_password\":\"" + ownerPassword + "\"}"))
                .andExpect(status().isNoContent());

        MvcResult result = deleteAccount(ownerToken, ownerPassword);
        assertThat(result.getResponse().getStatus()).isEqualTo(204);

        Establishment establishment = establishmentRepository
                .findById(UUID.fromString(establishmentId)).orElseThrow();

        assertThat(establishment.getOpenStatus())
                .as("a loja deixa de estar aberta AGORA")
                .isEqualTo(OpenStatus.CLOSED);
        assertThat(establishment.getStatus())
                .as("e o que REALMENTE tira a loja da vitrine — as queries publicas filtram "
                        + "o status, nao o openStatus")
                .isEqualTo(EstablishmentStatus.REMOVED);
        assertThat(establishment.getName()).isEqualTo("Loja removida");

        assertThat(professionalRepository.findByEstablishmentId(UUID.fromString(establishmentId)))
                .as("todo profissional foi desafiliado pelo LeaveEstablishmentUseCase")
                .isEmpty();

        Professional professional = professionalRepository
                .findById(UUID.fromString(ownerId)).orElseThrow();
        assertThat(professional.getEstablishment()).isNull();
        assertThat(professional.getIsAvailable()).isFalse();

        BaseUser deleted = userRepository.findById(UUID.fromString(ownerId)).orElseThrow();
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getEmail()).isNotEqualTo(ownerEmail);
    }

    @Test
    @Order(31)
    @DisplayName("A loja apagada nao vira registro fantasma — ela e os atendimentos dela continuam la")
    void delete_ownerKeepsEstablishmentHistory() {
        assertThat(establishmentRepository.findById(UUID.fromString(establishmentId)))
                .as("os atendimentos da loja pertencem tambem aos clientes e aos profissionais")
                .isPresent();
        assertThat(attendanceRepository.findById(clientAttendanceId)).isPresent();
    }

    /**
     * Regressao: a loja ficou SEM dono, e os mappers faziam {@code getOwner().getId()} cru. Bastava
     * uma loja orfa para a vitrine publica inteira responder 500 — para TODO usuario do app, nao so
     * para quem apagou a conta.
     *
     * <p>Desde o desativar-conta (d) a loja sem dono nao nasce mais: o dono apaga a loja (REMOVED)
     * antes de apagar a conta, e a loja removida guarda o vinculo com a conta anonimizada — e o
     * historico. O que segue valendo: a vitrine e o detalhe nao quebram.</p>
     */
    @Test
    @Order(32)
    @DisplayName("A vitrine publica nao quebra depois que o dono apaga a loja e a conta")
    void delete_publicListingSurvivesOwnerlessEstablishment() throws Exception {
        mvc.perform(get("/establishment"))
                .andExpect(status().isOk());

        // As duas rotas sao permitAll — de proposito, sem token: a vitrine e anonima, e o 500
        // atingia justamente quem nem tem conta.
        // `ownerId` (camelCase — este campo nao tem @JsonProperty, ao contrario de `open_status`)
        // segue o da conta anonimizada: a loja removida nao perde o vinculo.
        mvc.perform(get("/establishment/id/" + establishmentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ownerId))
                .andExpect(jsonPath("$.open_status").value("CLOSED"));
    }

    @Test
    @Order(33)
    @DisplayName("A loja orfa nao aparece mais na vitrine publica")
    void delete_ownerlessEstablishmentLeavesTheShowcase() throws Exception {
        mvc.perform(get("/establishment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + establishmentId + "')]").isEmpty());
    }

    @AfterAll
    void tearDown() {
        attendanceRepository.deleteAll();
        userRepository.deleteAll();
    }
}
