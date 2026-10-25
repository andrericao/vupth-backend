package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.Account;
import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.PixFee;
import com.ospaulos.vupth.api.admin.dto.AdminFinanceDtos.TotalsReport;
import com.ospaulos.vupth.domain.finance.service.FinanceAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * As abas de dinheiro do Painel (ledger-por-conta, 24/10): {@code GET /admin/finance/accounts} (as contas e a tarifa de Pix de
 * cada uma), {@code PUT /admin/finance/accounts/{key}} (muda a tarifa) e {@code GET /admin/finance/totals} (os totais de uma conta
 * ou de todas juntas). So ADMIN com a permissao FINANCE.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/finance")
public class AdminFinanceAccountsController {

    private final FinanceAccountService service;

    @GetMapping("/accounts")
    public ResponseEntity<List<Account>> accounts() {
        return ResponseEntity.ok(service.list());
    }

    @PutMapping("/accounts/{key}")
    public ResponseEntity<Account> setPixFee(@PathVariable String key, @RequestBody @Valid PixFee body) {
        return ResponseEntity.ok(service.setPixFee(key, body.pixFee()));
    }

    @GetMapping("/totals")
    public ResponseEntity<TotalsReport> totals(
            @RequestParam(required = false, defaultValue = "all") String account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return ResponseEntity.ok(service.totals(account, from, to));
    }
}
