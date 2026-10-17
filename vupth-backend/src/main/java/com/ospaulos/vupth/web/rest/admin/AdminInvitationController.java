package com.ospaulos.vupth.web.rest.admin;

import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.AcceptRequest;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.AcceptResponse;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.AdminMember;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.InvitationResponse;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.InviteRequest;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.PermissionsRequest;
import com.ospaulos.vupth.domain.admin.service.AdminInvitationService;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * O convite e os usuarios do painel (convite-com-permissoes, painel fatia 3). Tudo exige a permissao ADMIN_USERS,
 * menos {@code POST /admin/invitations/accept}, que e publico (o token do e-mail e a prova) e tem limite por IP.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin")
public class AdminInvitationController {

    private final AdminInvitationService service;

    @PostMapping("/invitations")
    public ResponseEntity<InvitationResponse> invite(@AuthenticationPrincipal UserDetailsImpl user,
                                                     @RequestBody @Valid InviteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.invite(user.getUserId(), request.email(), request.permissions()));
    }

    @GetMapping("/invitations")
    public ResponseEntity<List<InvitationResponse>> pending() {
        return ResponseEntity.ok(service.pending());
    }

    @DeleteMapping("/invitations/{id}")
    public ResponseEntity<Void> revoke(@PathVariable UUID id) {
        service.revoke(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/invitations/accept")
    public ResponseEntity<AcceptResponse> accept(@RequestBody @Valid AcceptRequest request) {
        return ResponseEntity.ok(service.accept(request.token(), request.password()));
    }

    @GetMapping("/users-admin")
    public ResponseEntity<List<AdminMember>> members() {
        return ResponseEntity.ok(service.members());
    }

    @PatchMapping("/users-admin/{userId}")
    public ResponseEntity<Void> changePermissions(@AuthenticationPrincipal UserDetailsImpl user,
                                                  @PathVariable UUID userId,
                                                  @RequestBody @Valid PermissionsRequest request) {
        service.changePermissions(user.getUserId(), userId, request.permissions());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/users-admin/{userId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal UserDetailsImpl user, @PathVariable UUID userId) {
        service.remove(user.getUserId(), userId);
        return ResponseEntity.noContent().build();
    }
}
