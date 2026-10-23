package com.ospaulos.vupth.infrastructure.sentry;

import com.ospaulos.vupth.infrastructure.config.GitCommitInfoContributor;
import com.ospaulos.vupth.infrastructure.security.jwt.filter.UserDetailsImpl;
import io.sentry.IScope;
import io.sentry.ScopeCallback;
import io.sentry.Scope;
import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@DisplayName("sentry-contexto-e-limpeza: papel, loja e atendimento no erro; release = commit")
class SentryContextFilterTest {

    private static final String ATT = "6f1c2b1e-0a6f-4a31-9a55-0d2f8f7c1a11";
    private static final String EST = "0a1b2c3d-1111-4222-8333-444455556666";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Scope run(String uri, String... roles) throws Exception {
        if (roles.length > 0) {
            UserDetailsImpl user = mock(UserDetailsImpl.class);
            List<SimpleGrantedAuthority> auth = java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            when(user.getAuthorities()).thenAnswer(i -> auth);
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, auth));
        }
        Scope scope = new Scope(new SentryOptions());
        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            new SentryContextFilter().doFilter(new MockHttpServletRequest("GET", uri), new MockHttpServletResponse(), new MockFilterChain());
            ArgumentCaptor<ScopeCallback> captor = ArgumentCaptor.forClass(ScopeCallback.class);
            sentry.verify(() -> Sentry.configureScope(captor.capture()));
            captor.getValue().run((IScope) scope);
        }
        return scope;
    }

    @Test
    @DisplayName("cliente num atendimento: role=client e attendance_id, sem usuario")
    void clientOnAttendance() throws Exception {
        Scope scope = run("/attendances/" + ATT + "/payment", "CLIENT");

        assertThat(scope.getTags()).containsEntry("role", "client").containsEntry("attendance_id", ATT)
                .doesNotContainKey("establishment_id");
        assertThat(scope.getUser()).isNull();
    }

    @Test
    @DisplayName("dono numa loja: role=owner e establishment_id; o papel mais alto vence")
    void ownerOnEstablishment() throws Exception {
        Scope scope = run("/establishments/" + EST + "/queue", "CLIENT", "OWNER");

        assertThat(scope.getTags()).containsEntry("role", "owner").containsEntry("establishment_id", EST);
    }

    @Test
    @DisplayName("admin e sem login")
    void adminAndAnonymous() throws Exception {
        assertThat(run("/admin/finance", "ADMIN").getTags()).containsEntry("role", "admin");
        SecurityContextHolder.clearContext();
        assertThat(run("/auth/login").getTags()).containsEntry("role", "anonymous");
    }

    @Test
    @DisplayName("release do Sentry = commit da build; sem commit fica sem release")
    void releaseIsTheCommit() {
        SentryOptions options = new SentryOptions();
        new SentryConfig().sentryRelease(new GitCommitInfoContributor("abcdef1234567", "", 0L)).configure(options);
        assertThat(options.getRelease()).isEqualTo("abcdef1");

        SentryOptions unknown = new SentryOptions();
        new SentryConfig().sentryRelease(new GitCommitInfoContributor("", "", 0L)).configure(unknown);
        assertThat(unknown.getRelease()).isNull();
    }
}
