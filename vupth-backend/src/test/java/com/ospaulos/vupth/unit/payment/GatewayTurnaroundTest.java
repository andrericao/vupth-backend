package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.domain.payment.exception.AccountIncompleteException;
import com.ospaulos.vupth.domain.payment.service.AccountCompleteGate;
import com.ospaulos.vupth.domain.payment.service.GatewayNoticeNotifier;
import com.ospaulos.vupth.domain.payment.service.PaymentAccountService;
import com.ospaulos.vupth.domain.payment.service.PaymentGatewayService;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** virada-do-gateway (DEC-163/164): aviso de 7 dias ao ligar o Asaas REAL, a regra so muda no fim do prazo e o CPF barra a abertura. */
class GatewayTurnaroundTest {

    private static final String REAL = "https://api.asaas.com/v3";
    private static final String SANDBOX = "https://api-sandbox.asaas.com/v3";

    private final AsaasClient client = mock(AsaasClient.class);
    private final AppSettingService settings = mock(AppSettingService.class);
    private final GatewayNoticeNotifier notifier = mock(GatewayNoticeNotifier.class);

    private PaymentGatewayService gateway(String url, boolean flag, String connectedAt) {
        when(settings.flag(AppSettingService.ASAAS_CONNECTED, false)).thenReturn(flag);
        when(settings.text(PaymentGatewayService.CONNECTED_AT)).thenReturn(connectedAt);
        PaymentGatewayService g = new PaymentGatewayService(new AsaasProperties("k", url, ""), client, settings,
                mock(PaymentAccountService.class));
        ReflectionTestUtils.setField(g, "noticeNotifier", notifier);
        return g;
    }

    @Test
    @DisplayName("ligar o REAL grava o dia, avisa os profissionais e a regra de recebimento ainda nao muda (aviso aberto)")
    void connectingRealOpensTheNotice() {
        PaymentGatewayService g = gateway(REAL, false, null);

        g.setConnected(true, UUID.randomUUID());

        verify(settings).setText(eq(PaymentGatewayService.CONNECTED_AT), anyString(), any());
        verify(notifier).notifyProfessionals(any(OffsetDateTime.class));
    }

    @Test
    @DisplayName("dentro dos 7 dias: o aviso existe, connected() e false e o CPF ainda nao e exigido")
    void insideTheNotice() {
        PaymentGatewayService g = gateway(REAL, true, OffsetDateTime.now().minusDays(2).toString());

        assertThat(g.notice()).isPresent();
        assertThat(g.notice().get().changesAt()).isAfter(OffsetDateTime.now().plusDays(4));
        assertThat(g.connected()).isFalse();
        assertThat(g.cpfRequired()).isFalse();
    }

    @Test
    @DisplayName("passados os 7 dias: sem aviso, connected() e true e o CPF passa a ser exigido")
    void afterTheNotice() {
        PaymentGatewayService g = gateway(REAL, true, OffsetDateTime.now().minusDays(8).toString());

        assertThat(g.notice()).isEmpty();
        assertThat(g.connected()).isTrue();
        assertThat(g.cpfRequired()).isTrue();
    }

    @Test
    @DisplayName("sandbox liga na hora, sem aviso e sem exigir CPF (sem dinheiro real)")
    void sandboxIsImmediate() {
        PaymentGatewayService g = gateway(SANDBOX, false, null);
        g.setConnected(true, UUID.randomUUID());

        verify(notifier, never()).notifyProfessionals(any());
        PaymentGatewayService on = gateway(SANDBOX, true, null);
        assertThat(on.connected()).isTrue();
        assertThat(on.notice()).isEmpty();
        assertThat(on.cpfRequired()).isFalse();
    }

    @Test
    @DisplayName("desligar apaga o dia da conexao")
    void disconnectingClearsTheDay() {
        PaymentGatewayService g = gateway(REAL, true, OffsetDateTime.now().minusDays(1).toString());

        g.setConnected(false, UUID.randomUUID());

        verify(settings).setText(eq(PaymentGatewayService.CONNECTED_AT), eq(""), any());
        verify(notifier, never()).notifyProfessionals(any());
    }

    @Test
    @DisplayName("o portao do CPF: exige so quando a virada valeu; com CPF passa; sem CPF 422 ACCOUNT_INCOMPLETE")
    void cpfGate() {
        UserRepository users = mock(UserRepository.class);
        UUID withCpf = UUID.randomUUID();
        UUID without = UUID.randomUUID();
        BaseUser a = new BaseUser();
        a.setCpf("52998224725");
        BaseUser b = new BaseUser();
        when(users.findById(withCpf)).thenReturn(Optional.of(a));
        when(users.findById(without)).thenReturn(Optional.of(b));

        AccountCompleteGate waiting = new AccountCompleteGate(gateway(REAL, true, OffsetDateTime.now().minusDays(2).toString()), users);
        assertThatCode(() -> waiting.assertComplete(without)).as("dentro do prazo ainda abre").doesNotThrowAnyException();

        AccountCompleteGate live = new AccountCompleteGate(gateway(REAL, true, OffsetDateTime.now().minusDays(9).toString()), users);
        assertThatCode(() -> live.assertComplete(withCpf)).doesNotThrowAnyException();
        assertThatThrownBy(() -> live.assertComplete(without)).isInstanceOf(AccountIncompleteException.class);
    }

    @Test
    @DisplayName("o Painel recebe changes_at (conexao REAL + 7 dias); sandbox, desligado ou sem dia: vazio")
    void changesAtForThePanel() {
        OffsetDateTime opened = OffsetDateTime.now().minusDays(2);
        assertThat(gateway(REAL, true, opened.toString()).changesAtIfConnected()).hasValueSatisfying(
                at -> assertThat(at).isEqualTo(opened.plusDays(7)));
        assertThat(gateway(REAL, false, opened.toString()).changesAtIfConnected()).isEmpty();
        assertThat(gateway(SANDBOX, true, opened.toString()).changesAtIfConnected()).isEmpty();
        assertThat(gateway(REAL, true, null).changesAtIfConnected()).isEmpty();
    }
}
