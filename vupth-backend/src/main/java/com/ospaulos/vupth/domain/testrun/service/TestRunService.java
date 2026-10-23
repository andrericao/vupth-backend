package com.ospaulos.vupth.domain.testrun.service;

import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.AttendanceLine;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Detail;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Row;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Store;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.testrun.exception.TestRunException;
import com.ospaulos.vupth.domain.testrun.model.TestRun;
import com.ospaulos.vupth.domain.testrun.repository.TestRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * As rodadas de teste (modo-piloto-sem-cobranca, DEC-106; varias lojas, DEC-108). O ADMIN inicia UM teste com nome e escolhe as
 * lojas; uma loja pode sair antes do fim (volta a funcionar normalmente, sem encerrar as outras) e "finalizar" tira todas. Uma
 * loja so esta em um teste ativo. {@link #tag} marca o atendimento NOVO com a flag test e o id do teste da loja: a marca vem na
 * criacao e nunca muda. Os valores sao simbolicos (fora do livro-caixa e do dinheiro real do painel), mas a multa funciona
 * como no real: a gerada e a pendente que entra num atendimento de teste sao cobradas (resposta do Andre, 22/10).
 */
@Slf4j
@Service
public class TestRunService {

    private static final int MAX_SIZE = 100;

    private final TestRunRepository runs;
    private final EstablishmentRepository establishments;
    private final JdbcTemplate jdbc;

    public TestRunService(TestRunRepository runs, EstablishmentRepository establishments, JdbcTemplate jdbc) {
        this.runs = runs;
        this.establishments = establishments;
        this.jdbc = jdbc;
    }

    /** Inicia o teste com as lojas escolhidas. 409 TEST_RUN_ALREADY_ACTIVE se alguma ja esta em outro teste ativo. */
    @Transactional
    public Row start(String name, List<UUID> establishmentIds, UUID adminId) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>(establishmentIds);
        for (UUID id : ids) {
            establishments.findById(id).orElseThrow(() -> new EstablishmentNotFoundException(id));
            if (activeRunIdOf(id).isPresent()) {
                throw new TestRunException("TEST_RUN_ALREADY_ACTIVE",
                        "Uma das lojas já está em um teste ativo. Tire-a de lá ou encerre esse teste antes.");
            }
        }
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setName(name.trim());
        run.setStartedAt(OffsetDateTime.now());
        run.setCreatedBy(adminId);
        runs.saveAndFlush(run);
        for (UUID id : ids) {
            jdbc.update("INSERT INTO test_run_store (test_run_id, establishment_id, joined_at) VALUES (?, ?, ?)",
                    run.getId(), id, java.sql.Timestamp.from(run.getStartedAt().toInstant()));
        }
        log.info("[Teste] iniciado — run={} nome='{}' lojas={}", run.getId(), run.getName(), ids.size());
        return row(run.getId());
    }

    /** Tira uma loja do teste em andamento: ela volta ao normal; as outras seguem. Se era a ultima, o teste termina. */
    @Transactional
    public Row leave(UUID runId, UUID establishmentId) {
        TestRun run = active(runId);
        int changed = jdbc.update("UPDATE test_run_store SET left_at = ? WHERE test_run_id = ? AND establishment_id = ? "
                        + "AND left_at IS NULL",
                java.sql.Timestamp.from(OffsetDateTime.now().toInstant()), runId, establishmentId);
        if (changed == 0) {
            throw new TestRunException("TEST_RUN_STORE_NOT_IN_TEST", "Esta loja não está neste teste.");
        }
        Long remaining = jdbc.queryForObject(
                "SELECT count(*) FROM test_run_store WHERE test_run_id = ? AND left_at IS NULL", Long.class, runId);
        if (remaining != null && remaining == 0) {
            run.setEndedAt(OffsetDateTime.now());
            runs.saveAndFlush(run);
        }
        log.info("[Teste] loja saiu — run={} establishment={} restam={}", runId, establishmentId, remaining);
        return row(runId);
    }

    /** "Finalizar teste": encerra o teste e tira todas as lojas que ainda estavam nele. */
    @Transactional
    public Row end(UUID runId) {
        TestRun run = active(runId);
        OffsetDateTime now = OffsetDateTime.now();
        run.setEndedAt(now);
        runs.saveAndFlush(run);
        jdbc.update("UPDATE test_run_store SET left_at = ? WHERE test_run_id = ? AND left_at IS NULL",
                java.sql.Timestamp.from(now.toInstant()), runId);
        log.info("[Teste] encerrado — run={}", runId);
        return row(runId);
    }

    /** Marca o atendimento que esta sendo criado, se a loja esta num teste ativo. Chame ANTES do primeiro save. */
    public void tag(Attendance attendance) {
        if (attendance.getEstablishment() == null || attendance.getEstablishment().getId() == null) {
            return;
        }
        activeRunIdOf(attendance.getEstablishment().getId()).ifPresent(runId -> {
            attendance.setTest(true);
            attendance.setTestRunId(runId);
        });
    }

    @Transactional(readOnly = true)
    public AdminPage<Row> list(String q, int page, int size) {
        int s = Math.max(1, Math.min(size, MAX_SIZE)), p = Math.max(page, 0);
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        long total = jdbc.queryForObject(
                "SELECT count(*) FROM test_run WHERE ?::text IS NULL OR lower(name) LIKE ?", Long.class, like, like);
        List<Row> items = jdbc.query(ROW_SQL + """
                 WHERE ?::text IS NULL OR lower(r.name) LIKE ?
                 ORDER BY r.started_at DESC, r.id
                 LIMIT ? OFFSET ?
                """, (rs, i) -> toRow(rs), like, like, s, p * s).stream().map(this::withStores).toList();
        return new AdminPage<>(items, p, s, total);
    }

    @Transactional(readOnly = true)
    public Detail detail(UUID runId) {
        Row run = row(runId);
        List<AttendanceLine> lines = jdbc.query("""
                SELECT id, status, payment_status, total_value, scheduled_time FROM attendance
                 WHERE test_run_id = ? ORDER BY scheduled_time DESC
                """, (rs, i) -> new AttendanceLine((UUID) rs.getObject("id"), rs.getString("status"),
                rs.getString("payment_status"), rs.getBigDecimal("total_value"),
                rs.getObject("scheduled_time", OffsetDateTime.class)), runId);
        return new Detail(run, lines);
    }

    /** O teste ativo em que a loja esta agora (id), se houver. */
    @Transactional(readOnly = true)
    public Optional<UUID> activeRunIdOf(UUID establishmentId) {
        List<UUID> ids = jdbc.query("""
                SELECT r.id FROM test_run r JOIN test_run_store s ON s.test_run_id = r.id
                 WHERE s.establishment_id = ? AND s.left_at IS NULL AND r.ended_at IS NULL LIMIT 1
                """, (rs, i) -> (UUID) rs.getObject("id"), establishmentId);
        return ids.stream().findFirst();
    }

    private TestRun active(UUID runId) {
        TestRun run = runs.findById(runId)
                .orElseThrow(() -> new TestRunException("TEST_RUN_NOT_FOUND", "Teste não encontrado."));
        if (!run.isActive()) {
            throw new TestRunException("TEST_RUN_ALREADY_ENDED", "Este teste já foi encerrado.");
        }
        return run;
    }

    private Row row(UUID runId) {
        List<Row> rows = jdbc.query(ROW_SQL + " WHERE r.id = ?", (rs, i) -> toRow(rs), runId);
        if (rows.isEmpty()) {
            throw new TestRunException("TEST_RUN_NOT_FOUND", "Teste não encontrado.");
        }
        return withStores(rows.get(0));
    }

    private Row withStores(Row r) {
        List<Store> stores = jdbc.query("""
                SELECT s.establishment_id, e.name, s.joined_at, s.left_at
                  FROM test_run_store s LEFT JOIN establishment e ON e.id = s.establishment_id
                 WHERE s.test_run_id = ? ORDER BY s.joined_at, e.name
                """, (rs, i) -> new Store((UUID) rs.getObject("establishment_id"), rs.getString("name"),
                rs.getObject("joined_at", OffsetDateTime.class), rs.getObject("left_at", OffsetDateTime.class)), r.id());
        return new Row(r.id(), r.name(), stores, r.startedAt(), r.endedAt(), r.active(), r.attendances(), r.symbolicAmount());
    }

    private static final String ROW_SQL = """
            SELECT r.id, r.name, r.started_at, r.ended_at,
                   (SELECT count(*) FROM attendance a WHERE a.test_run_id = r.id) AS attendances,
                   (SELECT coalesce(sum(a.total_value),0) FROM attendance a
                     WHERE a.test_run_id = r.id AND a.status = 'FINISHED') AS amount
              FROM test_run r
            """;

    private static Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
        return new Row((UUID) rs.getObject("id"), rs.getString("name"), List.of(),
                rs.getObject("started_at", OffsetDateTime.class), ended,
                ended == null, rs.getLong("attendances"), rs.getBigDecimal("amount"));
    }
}
