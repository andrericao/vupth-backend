package com.ospaulos.vupth.web.rest.testing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.client.repository.ClientRepository;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * multa-pendente-de-teste (CLI-23): o Maestro do "Contestar" precisa de uma conta com uma multa pendente de menos de 7 dias, e nao ha
 * como cria-la pela API sem um profissional marcando atraso. Este endpoint semeia essa multa para o PROPRIO cliente logado.
 *
 * <p><b>Nunca na producao:</b> o bean so existe com {@code vupth.test-seed.enabled=true}, que so o perfil de staging e os testes ligam. Sem a
 * propriedade (producao, dev local) a rota nem existe. Cria so uma multa para quem chama, nunca para outra conta.</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/test")
@ConditionalOnProperty(prefix = "vupth.test-seed", name = "enabled", havingValue = "true")
public class TestSeedController {

    static final BigDecimal AMOUNT = new BigDecimal("5.00");

    private final ClientRepository clients;
    private final ClientPenaltyEventRepository penalties;

    public record SeededPenalty(@JsonProperty("penalty_id") UUID penaltyId,
                                @JsonProperty("amount") BigDecimal amount,
                                @JsonProperty("created_at") OffsetDateTime createdAt) {}

    /** Uma multa de atraso (R$ 5,00, pendente, de agora) para o cliente logado. 404 se a conta nao for de cliente. */
    @PostMapping("/penalties")
    public ResponseEntity<SeededPenalty> seedPenalty(@AuthenticationPrincipal UserDetailsImpl user) {
        var client = clients.findById(user.getUserId()).orElse(null);
        if (client == null) {
            return ResponseEntity.notFound().build();
        }
        var saved = penalties.save(new ClientPenaltyEvent(client, PenaltyType.LATE_ARRIVAL, null, 0, AMOUNT));
        log.info("[TestSeed] multa de teste {} para o client={}", saved.getId(), user.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new SeededPenalty(saved.getId(), saved.getAmount(), saved.getCreatedAt()));
    }
}
