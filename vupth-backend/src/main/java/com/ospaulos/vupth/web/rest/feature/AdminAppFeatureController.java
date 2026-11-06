package com.ospaulos.vupth.web.rest.feature;

import com.ospaulos.vupth.api.feature.dto.AppFeatureHistoryResponse;
import com.ospaulos.vupth.api.feature.dto.AppFeatureResponse;
import com.ospaulos.vupth.api.feature.dto.AppFeatureUpdateRequest;
import com.ospaulos.vupth.domain.feature.AppFeatureService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * recursos-infraestrutura: as chaves no painel. A rota nao tem permissao em {@code AdminPermission}, entao so o dono entra: convidado
 * leva 403 mesmo com todas as permissoes.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/app-features")
public class AdminAppFeatureController {

    private final AppFeatureService service;

    @GetMapping
    public ResponseEntity<List<AppFeatureResponse>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PutMapping("/{key}")
    public ResponseEntity<AppFeatureResponse> update(@PathVariable String key, @RequestBody @Valid AppFeatureUpdateRequest request,
                                                     @AuthenticationPrincipal UserDetailsImpl admin) {
        return ResponseEntity.ok(service.update(key, request, admin.getUserId()));
    }

    @GetMapping("/{key}/history")
    public ResponseEntity<List<AppFeatureHistoryResponse>> history(@PathVariable String key) {
        return ResponseEntity.ok(service.history(key));
    }
}
