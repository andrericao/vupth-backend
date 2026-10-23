package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AdminPage;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Detail;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.Row;
import com.ospaulos.vupth.api.admin.dto.AdminTestRunDtos.StartRequest;
import com.ospaulos.vupth.domain.testrun.service.TestRunService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Os testes (rodadas) do painel (modo-piloto-sem-cobranca, DEC-106). So ADMIN, com a permissao ESTABLISHMENTS; cada
 * pedido fica em {@code admin_audit}. Nada aparece nos apps: so o painel declara e le os testes.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminTestRunController {

    private final TestRunService service;

    /**
     * Inicia UM teste com nome e as lojas escolhidas (DEC-108). 201; 409 TEST_RUN_ALREADY_ACTIVE se alguma loja ja esta em
     * outro teste ativo; 404 se a loja nao existe.
     */
    @PostMapping("/test-runs")
    public ResponseEntity<Row> start(@AuthenticationPrincipal UserDetailsImpl user, @RequestBody @Valid StartRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.start(request.name(), request.establishmentIds(), user.getUserId()));
    }

    /** Tira UMA loja do teste em andamento (ela volta ao normal; as outras seguem). 409 se a loja nao esta no teste. */
    @DeleteMapping("/test-runs/{id}/establishments/{establishmentId}")
    public ResponseEntity<Row> leave(@PathVariable UUID id, @PathVariable UUID establishmentId) {
        return ResponseEntity.ok(service.leave(id, establishmentId));
    }

    /** "Finalizar teste": encerra o teste e tira todas as lojas. 409 TEST_RUN_ALREADY_ENDED se ja acabou. */
    @PostMapping("/test-runs/{id}/end")
    public ResponseEntity<Row> end(@PathVariable UUID id) {
        return ResponseEntity.ok(service.end(id));
    }

    /** Os testes, do mais novo ao mais antigo; {@code q} busca pelo nome. */
    @GetMapping("/test-runs")
    public ResponseEntity<AdminPage<Row>> list(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.list(q, page, size));
    }

    /** Um teste e os atendimentos dele (valores simbolicos). */
    @GetMapping("/test-runs/{id}")
    public ResponseEntity<Detail> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(service.detail(id));
    }
}
