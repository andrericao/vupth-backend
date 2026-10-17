package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.AcceptResponse;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.AdminMember;
import com.ospaulos.vupth.api.admin.dto.AdminInvitationDtos.InvitationResponse;
import com.ospaulos.vupth.api.user.dto.request.profile.UserRegisterRequest;
import com.ospaulos.vupth.api.user.usecase.profile.RegisterUseCase;
import com.ospaulos.vupth.domain.admin.exception.AdminInvitationException;
import com.ospaulos.vupth.domain.admin.model.AdminInvitation;
import com.ospaulos.vupth.domain.admin.model.AdminPermission;
import com.ospaulos.vupth.domain.admin.model.AdminPermissionGrant;
import com.ospaulos.vupth.domain.admin.repository.AdminInvitationRepository;
import com.ospaulos.vupth.domain.admin.repository.AdminPermissionRepository;
import com.ospaulos.vupth.domain.admin.repository.AdminRecoveryCodeRepository;
import com.ospaulos.vupth.domain.admin.repository.AdminTotpRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.logging.LogMask;
import com.ospaulos.vupth.infrastructure.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * O convite para o painel e a gestao dos usuarios dele (convite-com-permissoes, painel fatia 3). O Andre convida por
 * e-mail escolhendo as permissoes; o convidado cria a senha (ou, se ja tem conta, ganha o papel) e cadastra o segundo
 * fator no primeiro acesso. O dono (ADMIN sem nenhuma linha de permissao) nao pode ser alterado nem removido.
 */
@Slf4j
@Service
public class AdminInvitationService {

    static final int VALID_DAYS = 7;

    private final AdminInvitationRepository invitations;
    private final AdminPermissionRepository permissions;
    private final AdminTotpRepository totps;
    private final AdminRecoveryCodeRepository recoveryCodes;
    private final UserRepository userRepository;
    private final RegisterUseCase registerUseCase;
    private final EmailSender emailSender;
    private final JdbcTemplate jdbc;
    private final String linkBaseUrl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public AdminInvitationService(AdminInvitationRepository invitations, AdminPermissionRepository permissions,
                                  AdminTotpRepository totps, AdminRecoveryCodeRepository recoveryCodes,
                                  UserRepository userRepository, RegisterUseCase registerUseCase,
                                  EmailSender emailSender, JdbcTemplate jdbc,
                                  @Value("${vupth.admin.invite-link-base-url:https://vupth-painel.pages.dev/convite}")
                                  String linkBaseUrl) {
        this(invitations, permissions, totps, recoveryCodes, userRepository, registerUseCase, emailSender, jdbc,
                linkBaseUrl, Clock.systemUTC());
    }

    AdminInvitationService(AdminInvitationRepository invitations, AdminPermissionRepository permissions,
                           AdminTotpRepository totps, AdminRecoveryCodeRepository recoveryCodes,
                           UserRepository userRepository, RegisterUseCase registerUseCase,
                           EmailSender emailSender, JdbcTemplate jdbc, String linkBaseUrl, Clock clock) {
        this.invitations = invitations;
        this.permissions = permissions;
        this.totps = totps;
        this.recoveryCodes = recoveryCodes;
        this.userRepository = userRepository;
        this.registerUseCase = registerUseCase;
        this.emailSender = emailSender;
        this.jdbc = jdbc;
        this.linkBaseUrl = linkBaseUrl;
        this.clock = clock;
    }

    // ─── convite ───

    @Transactional
    public InvitationResponse invite(UUID inviterId, String email, List<AdminPermission> wanted) {
        Set<AdminPermission> perms = requirePermissions(wanted);
        String normalized = email.trim().toLowerCase();
        if (isAdmin(normalized)) {
            throw new AdminInvitationException(HttpStatus.CONFLICT, "ADMIN_ALREADY_MEMBER",
                    "Esta pessoa ja usa o painel.");
        }
        invitations.findByEmailIgnoreCaseAndAcceptedAtIsNullAndRevokedAtIsNull(normalized).forEach(old -> {
            old.setRevokedAt(OffsetDateTime.now(clock));
            invitations.save(old);
        });

        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        AdminInvitation invitation = new AdminInvitation();
        invitation.setId(UUID.randomUUID());
        invitation.setEmail(normalized);
        invitation.setTokenHash(hash(token));
        invitation.setPermissions(join(perms));
        invitation.setInvitedBy(inviterId);
        invitation.setCreatedAt(OffsetDateTime.now(clock));
        invitation.setExpiresAt(OffsetDateTime.now(clock).plusDays(VALID_DAYS));
        invitations.save(invitation);

        emailSender.send(normalized, "Convite para o painel VUPTH",
                "Voce foi convidado para o painel VUPTH. Abra o link, crie a sua senha e cadastre o Google "
                        + "Authenticator (vale por " + VALID_DAYS + " dias, uma vez):\n" + linkBaseUrl + "?token=" + token);
        log.info("[AdminInvite] convite para {} com {}", LogMask.email(normalized), perms);
        return toResponse(invitation);
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> pending() {
        return invitations.findByAcceptedAtIsNullAndRevokedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                OffsetDateTime.now(clock)).stream().map(this::toResponse).toList();
    }

    @Transactional
    public void revoke(UUID invitationId) {
        AdminInvitation invitation = invitations.findById(invitationId).orElseThrow(() ->
                new AdminInvitationException(HttpStatus.NOT_FOUND, "ADMIN_INVITATION_NOT_FOUND", "Convite nao encontrado."));
        if (invitation.getAcceptedAt() == null && invitation.getRevokedAt() == null) {
            invitation.setRevokedAt(OffsetDateTime.now(clock));
            invitations.save(invitation);
        }
    }

    /** O convidado aceita: conta nova com a senha dada, ou, se a conta ja existe, so ganha o papel (senha intacta). */
    @Transactional
    public AcceptResponse accept(String token, String password) {
        AdminInvitation invitation = invitations.findByTokenHash(hash(token == null ? "" : token.trim()))
                .filter(i -> i.getAcceptedAt() == null && i.getRevokedAt() == null
                        && i.getExpiresAt().isAfter(OffsetDateTime.now(clock)))
                .orElseThrow(() -> new AdminInvitationException(HttpStatus.GONE, "ADMIN_INVITATION_INVALID",
                        "O convite nao vale mais. Peca outro."));

        BaseUser user;
        if (userRepository.existsByEmail(invitation.getEmail())) {
            user = userRepository.findByEmail(invitation.getEmail()).orElseThrow();
        } else {
            registerUseCase.executeRegister(new UserRegisterRequest(invitation.getEmail(), password));
            user = userRepository.findByEmail(invitation.getEmail()).orElseThrow();
        }
        user.addRole(RoleType.ADMIN);
        userRepository.save(user);
        replacePermissions(user.getId(), parse(invitation.getPermissions()));

        invitation.setAcceptedAt(OffsetDateTime.now(clock));
        invitations.save(invitation);
        return new AcceptResponse(invitation.getEmail(), "TOTP_SETUP");
    }

    // ─── usuarios do painel ───

    @Transactional(readOnly = true)
    public List<AdminMember> members() {
        return jdbc.query("""
                SELECT u.id, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS name, u.email,
                       coalesce((SELECT t.enabled FROM admin_totp t WHERE t.user_id = u.id), false) AS totp
                  FROM base_user u
                  JOIN user_roles r ON r.base_user_id = u.id AND r.role_type = 'ADMIN' AND r.is_active
                 ORDER BY u.email
                """, (rs, i) -> {
            UUID id = (UUID) rs.getObject("id");
            Set<AdminPermission> perms = permissions.permissionsOf(id);
            return new AdminMember(id, rs.getString("name"), rs.getString("email"), perms.isEmpty(),
                    perms.stream().sorted(Comparator.naturalOrder()).toList(), rs.getBoolean("totp"));
        });
    }

    @Transactional
    public void changePermissions(UUID actorId, UUID targetId, List<AdminPermission> wanted) {
        guardTarget(actorId, targetId);
        replacePermissions(targetId, requirePermissions(wanted));
    }

    /** Tira o acesso: o papel ADMIN, as permissoes e o segundo fator. A conta (cliente) continua. */
    @Transactional
    public void remove(UUID actorId, UUID targetId) {
        guardTarget(actorId, targetId);
        jdbc.update("DELETE FROM user_roles WHERE base_user_id = ? AND role_type = 'ADMIN'", targetId);
        permissions.deleteAll(permissions.findAll().stream().filter(g -> g.getUserId().equals(targetId)).toList());
        totps.deleteById(targetId);
        recoveryCodes.deleteByUserId(targetId);
    }

    private void guardTarget(UUID actorId, UUID targetId) {
        if (actorId.equals(targetId)) {
            throw new AdminInvitationException(HttpStatus.CONFLICT, "ADMIN_SELF_CHANGE",
                    "Voce nao pode mudar o seu proprio acesso.");
        }
        boolean isMember = members().stream().anyMatch(m -> m.id().equals(targetId));
        if (!isMember) {
            throw new AdminInvitationException(HttpStatus.NOT_FOUND, "ADMIN_MEMBER_NOT_FOUND",
                    "Esta pessoa nao usa o painel.");
        }
        if (permissions.permissionsOf(targetId).isEmpty()) {
            throw new AdminInvitationException(HttpStatus.CONFLICT, "ADMIN_OWNER_PROTECTED",
                    "O administrador principal nao pode ser alterado nem removido.");
        }
    }

    // ─── apoio ───

    private void replacePermissions(UUID userId, Set<AdminPermission> perms) {
        permissions.deleteAll(permissions.findAll().stream().filter(g -> g.getUserId().equals(userId)).toList());
        permissions.flush();
        for (AdminPermission permission : perms) {
            AdminPermissionGrant grant = new AdminPermissionGrant();
            grant.setId(UUID.randomUUID());
            grant.setUserId(userId);
            grant.setPermission(permission);
            permissions.save(grant);
        }
    }

    private static Set<AdminPermission> requirePermissions(List<AdminPermission> wanted) {
        if (wanted == null || wanted.isEmpty()) {
            throw new AdminInvitationException(HttpStatus.UNPROCESSABLE_ENTITY, "ADMIN_PERMISSIONS_REQUIRED",
                    "Escolha ao menos uma area do painel.");
        }
        return EnumSet.copyOf(wanted);
    }

    private boolean isAdmin(String email) {
        return userRepository.findByEmail(email).map(u -> u.hasRole(RoleType.ADMIN)).orElse(false);
    }

    private InvitationResponse toResponse(AdminInvitation i) {
        return new InvitationResponse(i.getId(), i.getEmail(), parse(i.getPermissions()).stream().sorted().toList(),
                i.getExpiresAt());
    }

    private static String join(Set<AdminPermission> perms) {
        return perms.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<AdminPermission> parse(String csv) {
        return java.util.Arrays.stream(csv.split(",")).filter(s -> !s.isBlank()).map(AdminPermission::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(AdminPermission.class)));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
