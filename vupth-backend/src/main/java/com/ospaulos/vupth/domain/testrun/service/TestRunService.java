package com.ospaulos.vupth.domain.testrun.service;

import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.AttendanceLine;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Detail;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Row;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * As rodadas de teste (modo-piloto-sem-cobranca, DEC-106). O ADMIN inicia uma rodada numa loja, com um nome, e a
 * encerra; so uma ativa por loja. {@link #tag} marca o atendimento NOVO com a flag test e o id da rodada ativa: a marca
 * vem na criacao e nunca muda. Os valores sao simbolicos: nada e cobrado a mais nem a menos, e o atendimento fica fora do
 * livro-caixa. Estrutura pronta para o futuro {@code flags-por-loja}: a rodada e que diz o que a loja esta fazendo.
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

    @Transactional
    public Row start(UUID establishmentId, String name, UUID adminId) {
        establishments.findById(establishmentId).orElseThrow(() -> new EstablishmentNotFoundException(establishmentId));
        if (runs.findByEstablishmentIdAndEndedAtIsNull(establishmentId).isPresent()) {
            throw new TestRunException("TEST_RUN_ALREADY_ACTIVE", "Esta loja já tem um teste ativo. Encerre-o antes de iniciar outro.");
        }
        TestRun run = new TestRun();
        run.setId(UUID.randomUUID());
        run.setName(name.trim());
        run.setEstablishmentId(establishmentId);
        run.setStartedAt(OffsetDateTime.now());
        run.setCreatedBy(adminId);
        runs.saveAndFlush(run);
        log.info("[Teste] iniciado — run={} nome='{}' establishment={}", run.getId(), run.getName(), establishmentId);
        return row(run.getId());
    }

    @Transactional
    public Row end(UUID runId) {
        TestRun run = runs.findById(runId)
                .orElseThrow(() -> new TestRunException("TEST_RUN_NOT_FOUND", "Teste não encontrado."));
        if (!run.isActive()) {
            throw new TestRunException("TEST_RUN_ALREADY_ENDED", "Este teste já foi encerrado.");
        }
        run.setEndedAt(OffsetDateTime.now());
        runs.saveAndFlush(run);
        log.info("[Teste] encerrado — run={}", runId);
        return row(runId);
    }

    /** Marca o atendimento que esta sendo criado, se a loja tem teste ativo. Chame ANTES do primeiro save. */
    public void tag(Attendance attendance) {
        if (attendance.getEstablishment() == null || attendance.getEstablishment().getId() == null) {
            return;
        }
        runs.findByEstablishmentIdAndEndedAtIsNull(attendance.getEstablishment().getId()).ifPresent(run -> {
            attendance.setTest(true);
            attendance.setTestRunId(run.getId());
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
                """, (rs, i) -> toRow(rs), like, like, s, p * s);
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

    /** O teste ativo de cada loja (id e nome) para a lista de lojas do painel; vazio quando nao ha. */
    @Transactional(readOnly = true)
    public java.util.Optional<TestRun> activeOf(UUID establishmentId) {
        return runs.findByEstablishmentIdAndEndedAtIsNull(establishmentId);
    }

    private Row row(UUID runId) {
        List<Row> rows = jdbc.query(ROW_SQL + " WHERE r.id = ?", (rs, i) -> toRow(rs), runId);
        if (rows.isEmpty()) {
            throw new TestRunException("TEST_RUN_NOT_FOUND", "Teste não encontrado.");
        }
        return rows.get(0);
    }

    private static final String ROW_SQL = """
            SELECT r.id, r.name, r.establishment_id, e.name AS establishment_name, r.started_at, r.ended_at,
                   (SELECT count(*) FROM attendance a WHERE a.test_run_id = r.id) AS attendances,
                   (SELECT coalesce(sum(a.total_value),0) FROM attendance a
                     WHERE a.test_run_id = r.id AND a.status = 'FINISHED') AS amount
              FROM test_run r LEFT JOIN establishment e ON e.id = r.establishment_id
            """;

    private static Row toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
        return new Row((UUID) rs.getObject("id"), rs.getString("name"), (UUID) rs.getObject("establishment_id"),
                rs.getString("establishment_name"), rs.getObject("started_at", OffsetDateTime.class), ended,
                ended == null, rs.getLong("attendances"), rs.getBigDecimal("amount"));
    }
}
