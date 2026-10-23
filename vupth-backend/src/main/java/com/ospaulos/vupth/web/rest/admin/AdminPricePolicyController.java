package com.ospaulos.vupth.web.rest.admin;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService;
import com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService.Preview;
import com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService.Published;
import com.ospaulos.vupth.domain.pricepolicy.service.PricePolicyService.Version;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * politica-de-preco-com-ciencia (DEC-100): o ADMIN ve o que mudaria ({@code /preview}), publica a politica (reajusta os servicos,
 * avisa os profissionais e abre o prazo de 7 dias) e acompanha quem ja registrou a ciencia. Nada acontece sozinho.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/price-policy")
public class AdminPricePolicyController {

    private final PricePolicyService service;

    public record PublishRequest(@NotBlank @Size(max = 2000) @JsonProperty("summary") String summary) {}

    @GetMapping("/preview")
    public ResponseEntity<Preview> preview() {
        return ResponseEntity.ok(service.preview());
    }

    @GetMapping
    public ResponseEntity<List<Version>> history() {
        return ResponseEntity.ok(service.history());
    }

    @PostMapping
    public ResponseEntity<Published> publish(@AuthenticationPrincipal UserDetailsImpl user,
                                             @RequestBody @Valid PublishRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.publish(request.summary(), user.getUserId()));
    }
}
