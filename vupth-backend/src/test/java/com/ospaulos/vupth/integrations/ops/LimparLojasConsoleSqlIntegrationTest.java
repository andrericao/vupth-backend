package com.ospaulos.vupth.integrations.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javafaker.Faker;
import com.ospaulos.vupth.VupthApplication;
import com.ospaulos.vupth.api.attendance.dto.request.AttendanceRequest;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.ServiceItemType;
import com.ospaulos.vupth.integrations.utils.TestUserUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * limpar-lojas-de-teste-producao (05/11, ficam 5 lojas): roda no banco de teste os tres SQL do console do Railway
 * ({@code ops/limpar-lojas-teste/console}). A listagem acha as 5 e as travas em zero; o ensaio termina no erro "ENSAIO OK" sem
 * apagar nada; a gravacao, numa transacao desfeita no fim, deixa so as 5 lojas sem quebrar chave estrangeira com o que as outras
 * classes deixaram no banco.
 */
@SpringBootTest(classes = VupthApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LimparLojasConsoleSqlIntegrationTest {

    private static final Path CONSOLE = Path.of("ops/limpar-lojas-teste/console");
    private static final List<String> KEEP = List.of("gueibinails", "Barbearia do Allan", "Nath sempre bela", "alexia cortes",
            "Rede Própria");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired EstablishmentRepository establishmentRepository;
    @Autowired ProfessionalRepository professionalRepository;

    private final Faker faker = new Faker();

    @Test
    @DisplayName("listar acha as 5 e as travas em zero; o ensaio nao apaga nada; gravar (desfeito) deixa so as 5")
    void consoleSql() throws Exception {
        for (String name : KEEP) {
            var owner = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
            String id = TestUserUtils.becomeOwner(mvc, mapper, faker, owner.token()).id();
            jdbc.update("UPDATE establishment SET name = ? WHERE id = ?", name, UUID.fromString(id));
        }
        String keptShop = jdbc.queryForObject("SELECT id::text FROM establishment WHERE name = 'gueibinails'", String.class);
        // uma loja que sai, com profissional, servico e um corte concluido (as linhas dependentes que o script apaga)
        var pro = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        String shop = TestUserUtils.becomeOwner(mvc, mapper, faker, pro.token()).id();
        TestUserUtils.openEstablishment(establishmentRepository, shop);
        TestUserUtils.becomeProfessional(mvc, mapper, faker, pro.token(), shop, "Beto");
        TestUserUtils.startShift(professionalRepository, shop);
        var item = TestUserUtils.createServiceItem(mvc, mapper, shop, pro.token(), ServiceItemType.CORTE_MAQUINA, "Corte",
                BigDecimal.valueOf(30));
        var client = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        AttendanceRequest request = new AttendanceRequest(UUID.fromString(pro.id()), UUID.fromString(shop),
                UUID.fromString(client.id()), List.of(UUID.fromString(item.id())), AttendanceStatus.SCHEDULED,
                OffsetDateTime.now().plusMinutes(5), 30, OffsetDateTime.now());
        String attendanceId = new JSONObject(mvc.perform(post("/attendances").contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", client.token()).content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).getString("id");
        mvc.perform(patch("/attendances/{id}/start", attendanceId).header("Authorization", pro.token())).andExpect(status().isOk());
        mvc.perform(patch("/attendances/{id}/finish", attendanceId).header("Authorization", pro.token())).andExpect(status().isOk());
        // o cliente que cortou tambem abriu uma loja que sai: so perde o papel de dono (foi cliente de um atendimento)
        TestUserUtils.becomeOwner(mvc, mapper, faker, client.token());
        // um ADMIN dono de loja que sai: a loja sai, a conta e os papeis nao
        var admin = TestUserUtils.registerAndLoginUser(mvc, mapper, faker);
        TestUserUtils.becomeOwner(mvc, mapper, faker, admin.token());
        jdbc.update("INSERT INTO user_roles (id, base_user_id, role_type, is_active) VALUES (gen_random_uuid(), ?, 'ADMIN', true)",
                UUID.fromString(admin.id()));

        // 1) listar
        // uma consulta por bloco "-- Consulta 1A/1B/1C/1D" (o console roda uma por vez)
        String[] listar = Files.readString(CONSOLE.resolve("1-listar.sql")).split("(?m)^(?=-- Consulta 1)");
        assertThat(listar).hasSize(5);
        jdbc.queryForList(listar[4]); // 1D (o que o Liquibase semeou) roda; no banco de teste as sementes existem
        List<Map<String, Object>> kept = jdbc.queryForList(listar[1]);
        assertThat(kept).hasSize(5).allSatisfy(row -> assertThat(row).containsKeys("aberta_ou_fechada", "profissionais"));
        Map<String, Object> totals = jdbc.queryForMap(listar[2]);
        assertThat(((Number) totals.get("lojas_que_ficam")).longValue()).isEqualTo(5);
        assertThat(((Number) totals.get("lojas_que_saem")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) totals.get("atendimentos_que_saem")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) totals.get("trava_atendimento_das_5")).longValue()).isZero();
        assertThat(((Number) totals.get("trava_profissional_que_sai_nas_5")).longValue()).isZero();
        assertThat(((Number) totals.get("trava_pedido_nas_5")).longValue()).isZero();
        assertThat(((Number) totals.get("trava_admin")).longValue()).isZero();
        assertThat(((Number) totals.get("trava_andre")).longValue()).isZero();
        assertThat(((Number) totals.get("trava_cliente")).longValue()).isZero();
        assertThat(((Number) totals.get("contas_que_saem")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) totals.get("contas_que_so_perdem_papel")).longValue()).isGreaterThanOrEqualTo(1);
        List<Map<String, Object>> leaving = jdbc.queryForList(listar[3]);
        assertThat(leaving).hasSize(((Number) totals.get("lojas_que_saem")).intValue())
                .anySatisfy(row -> assertThat(row.get("profissionais")).isEqualTo(1L));

        long shopsBefore = count("establishment");
        long attendancesBefore = count("attendance");

        // 2) ensaio: termina no erro "ENSAIO OK" e nao apaga nada
        String ensaio = Files.readString(CONSOLE.resolve("2-ensaio.sql"));
        assertThatThrownBy(() -> jdbc.execute(ensaio)).hasMessageContaining("ENSAIO OK, NADA GRAVADO")
                .hasMessageContaining("lojas que ficam: 5");
        assertThat(count("establishment")).isEqualTo(shopsBefore);
        assertThat(count("attendance")).isEqualTo(attendancesBefore);

        // 3) gravar, numa transacao desfeita: sobram so as 5, com o que e delas
        String gravar = Files.readString(CONSOLE.resolve("3-gravar.sql"));
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            jdbc.execute(gravar);
            assertThat(count("establishment")).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM establishment WHERE id = ?::uuid", Long.class, keptShop)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance WHERE id = ?::uuid", Long.class, attendanceId)).isZero();
            // dono/profissional que nunca foi cliente: sai, anonimizado como o "excluir conta"
            assertThat(jdbc.queryForObject("SELECT email FROM base_user WHERE id = ?::uuid", String.class, pro.id()))
                    .startsWith("deleted-").endsWith("@deleted.invalid");
            assertThat(roles(pro.id())).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM account_deletion_ledger WHERE id_hash = encode(sha256(?::bytea), 'hex')",
                    Long.class, pro.id())).isOne();
            // foi cliente de um atendimento: a conta fica, sem o papel de dono
            assertThat(jdbc.queryForObject("SELECT email FROM base_user WHERE id = ?::uuid", String.class, client.id()))
                    .doesNotStartWith("deleted-");
            assertThat(roles(client.id())).contains("CLIENT").doesNotContain("OWNER", "PROFESSIONAL");
            // ADMIN: nada muda na conta
            assertThat(roles(admin.id())).contains("ADMIN", "OWNER");
            // os donos das 5 seguem donos
            assertThat(jdbc.queryForObject("SELECT count(*) FROM establishment e JOIN user_roles r ON r.base_user_id = e.owner_id"
                    + " AND r.role_type = 'OWNER' AND r.is_active", Long.class)).isEqualTo(5);
            tx.setRollbackOnly();
        });
        assertThat(count("establishment")).isEqualTo(shopsBefore);
    }

    private List<String> roles(String userId) {
        return jdbc.queryForList("SELECT role_type FROM user_roles WHERE base_user_id = ?::uuid AND is_active", String.class, userId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    /** Os nomes das 5 sairiam de novo numa proxima rodada no mesmo banco; troca para nao contar 10. */
    @AfterAll
    void renameKept() {
        jdbc.update("UPDATE establishment SET name = name || ' (teste)' WHERE lower(trim(name)) IN "
                + "('gueibinails', 'barbearia do allan', 'nath sempre bela', 'alexia cortes', 'rede própria')");
    }
}
