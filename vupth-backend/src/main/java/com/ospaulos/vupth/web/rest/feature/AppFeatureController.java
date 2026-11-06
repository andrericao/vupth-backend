package com.ospaulos.vupth.web.rest.feature;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.feature.AppFeatureService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** recursos-infraestrutura: o que vale para quem chama. Nao vem a lista de chaves nem a regra: so as ligadas; a que falta esta desligada. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/features")
public class AppFeatureController {

    private final AppFeatureService service;

    public record MyFeatures(@JsonProperty("enabled") List<String> enabled) {
    }

    /** artigo-do-recurso-no-suporte: publico, sem login. O que nao vem aqui esta oculto. */
    @GetMapping("/articles")
    public ResponseEntity<List<com.ospaulos.vupth.api.feature.dto.AppFeatureArticleResponse>> articles() {
        return ResponseEntity.ok(service.articles());
    }

    @GetMapping("/me")
    public ResponseEntity<MyFeatures> mine(@AuthenticationPrincipal UserDetailsImpl user) {
        return ResponseEntity.ok(new MyFeatures(service.enabledFor(user.getUserId())));
    }
}
