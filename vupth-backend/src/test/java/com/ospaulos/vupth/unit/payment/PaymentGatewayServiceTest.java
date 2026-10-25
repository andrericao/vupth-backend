package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.payment.PaymentMethod;
import com.ospaulos.vupth.domain.payment.PaymentStatus;
import com.ospaulos.vupth.domain.payment.service.PaymentAccountService;
import com.ospaulos.vupth.domain.payment.service.PaymentGatewayService;
import com.ospaulos.vupth.domain.payment.service.PaymentService;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** asaas-sandbox-no-fluxo (DEC-119): a conexao liga e desliga; conectado, nasce a cobranca Pix; o webhook quita. */
class PaymentGatewayServiceTest {

    private static final String SANDBOX = "https://api-sandbox.asaas.com/v3";
    private final AsaasClient client = mock(AsaasClient.class);
    private final AppSettingService settings = mock(AppSettingService.class);

    private final PaymentAccountService paymentAccounts = mock(PaymentAccountService.class);

    private PaymentGatewayService gateway(String key, String url, boolean flag) {
        when(settings.flag(AppSettingService.ASAAS_CONNECTED, false)).thenReturn(flag);
        return new PaymentGatewayService(new AsaasProperties(key, url, ""), client, settings, paymentAccounts);
    }

    private Attendance attendance() {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        BaseUser user = new BaseUser();
        user.setId(UUID.randomUUID());
        user.setEmail("ana.cliente@vupth.test");
        user.setFirstName("Ana");
        user.setLastName("Cliente");
        a.setClient(new Client(user));
        return a;
    }

    private void asaasAnswers() {
        when(client.ensureCustomer(any(AsaasClient.Party.class), isNull())).thenReturn(new AsaasClient.Customer("cus_1", true));
        when(client.createCharge(eq("cus_1"), any(), eq("PIX"), anyString(), anyString(), any()))
                .thenReturn(new AsaasClient.Charge("pay_1", "PENDING", new BigDecimal("30.90"), "PIX", "https://asaas/i/1", "ref"));
        when(client.pixQrCode("pay_1")).thenReturn(new AsaasClient.PixQr("000201PIX", "base64img"));
    }

    @Test
    @DisplayName("conectado so com a chave E a flag ligada; o modo vem da URL")
    void connected() {
        assertThat(gateway("", SANDBOX, true).connected()).as("sem chave").isFalse();
        assertThat(gateway("k", SANDBOX, false).connected()).as("flag desligada (padrao)").isFalse();
        PaymentGatewayService on = gateway("k", SANDBOX, true);
        assertThat(on.connected()).isTrue();
        assertThat(on.mode()).isEqualTo("SANDBOX");
        assertThat(gateway("k", "https://api.asaas.com/v3", true).mode()).isEqualTo("REAL");
    }

    @Test
    @DisplayName("ligar sem chave nao da (422 ASAAS_NOT_CONFIGURED); com chave grava a flag, e desligar sempre pode")
    void setConnected() {
        PaymentGatewayService noKey = gateway("", SANDBOX, false);
        assertThatThrownBy(() -> noKey.setConnected(true, UUID.randomUUID())).isInstanceOfSatisfying(AsaasException.class,
                e -> assertThat(e.getCode()).isEqualTo("ASAAS_NOT_CONFIGURED"));
        verify(settings, never()).setFlag(anyString(), anyBoolean(), any());

        UUID admin = UUID.randomUUID();
        PaymentGatewayService withKey = gateway("k", SANDBOX, false);
        withKey.setConnected(true, admin);
        verify(settings).setFlag(AppSettingService.ASAAS_CONNECTED, true, admin);
        noKey.setConnected(false, admin);
        verify(settings).setFlag(AppSettingService.ASAAS_CONNECTED, false, admin);
    }

    @Test
    @DisplayName("conectado no sandbox: a cobranca Pix nasce com o QR, o modo e a referencia do atendimento")
    void createsCharge() {
        asaasAnswers();
        Attendance a = attendance();

        gateway("k", SANDBOX, true).ensureCharge(a, new BigDecimal("30.90"));

        assertThat(a.getAsaasPaymentId()).isEqualTo("pay_1");
        assertThat(a.getAsaasMode()).isEqualTo("SANDBOX");
        assertThat(a.getAsaasStatus()).isEqualTo("PENDING");
        assertThat(a.getAsaasPixPayload()).isEqualTo("000201PIX");
        assertThat(a.getAsaasPixImage()).isEqualTo("base64img");
        assertThat(a.getAsaasInvoiceUrl()).isEqualTo("https://asaas/i/1");
        verify(client).createCharge("cus_1", new BigDecimal("30.90"), "PIX", a.getId().toString(), "VUPTH - atendimento", List.of());
        // email-do-cliente-no-asaas: o cliente do Asaas e a pessoa logada, com o e-mail e o nome do cadastro
        org.mockito.ArgumentCaptor<AsaasClient.Party> party = org.mockito.ArgumentCaptor.forClass(AsaasClient.Party.class);
        verify(client).ensureCustomer(party.capture(), isNull());
        assertThat(party.getValue().reference()).isEqualTo("vupth-user-" + a.getClient().getId());
        assertThat(party.getValue().email()).isEqualTo("ana.cliente@vupth.test");
        assertThat(party.getValue().name()).isEqualTo("Ana Cliente");
    }

    @Test
    @DisplayName("desconectado, ja com cobranca, abaixo de R$ 5,00, sem cliente ou no real sem CPF: nada e criado (fica o fluxo de sempre)")
    void noCharge() {
        Attendance a = attendance();
        gateway("k", SANDBOX, false).ensureCharge(a, new BigDecimal("30.90"));
        gateway("k", SANDBOX, true).ensureCharge(a, new BigDecimal("4.99"));
        gateway("k", SANDBOX, true).ensureCharge(a, null);
        Attendance guest = attendance();
        guest.setClient(null);
        gateway("k", SANDBOX, true).ensureCharge(guest, new BigDecimal("30.90"));
        gateway("k", "https://api.asaas.com/v3", true).ensureCharge(a, new BigDecimal("30.90"));
        Attendance charged = attendance();
        charged.setAsaasPaymentId("pay_old");
        gateway("k", SANDBOX, true).ensureCharge(charged, new BigDecimal("30.90"));

        verifyNoInteractions(client);
        assertThat(a.getAsaasPaymentId()).isNull();
        assertThat(charged.getAsaasPaymentId()).as("cobranca aberta nao e trocada").isEqualTo("pay_old");
    }

    @Test
    @DisplayName("o Asaas recusa: nao quebra, nao grava nada, fica o fluxo de sempre")
    void asaasFailure() {
        when(client.ensureCustomer(any(AsaasClient.Party.class), isNull())).thenThrow(new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_REJECTED", "recusou"));
        Attendance a = attendance();

        gateway("k", SANDBOX, true).ensureCharge(a, new BigDecimal("30.90"));

        assertThat(a.getAsaasPaymentId()).isNull();
    }

    @Test
    @DisplayName("webhook: o Pix recebido quita o atendimento da cobranca (forma PIX); aviso repetido, de outra cobranca ou so de status nao quita")
    void webhookPays() {
        AttendanceRepository repo = mock(AttendanceRepository.class);
        PaymentService payments = new PaymentService(repo, mock(ClientPenaltyEventRepository.class), mock(UserRepository.class));
        Attendance a = attendance();
        a.setPaymentStatus(PaymentStatus.PENDING);
        a.setAsaasPaymentId("pay_1");
        when(repo.findById(a.getId())).thenReturn(Optional.of(a));

        assertThat(payments.onGatewayEvent(a.getId(), "pay_other", "RECEIVED", true)).as("outra cobranca").isFalse();
        assertThat(a.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        assertThat(payments.onGatewayEvent(a.getId(), "pay_1", "PENDING", false)).as("so status").isFalse();
        assertThat(a.getAsaasStatus()).isEqualTo("PENDING");
        assertThat(a.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        assertThat(payments.onGatewayEvent(a.getId(), "pay_1", "RECEIVED", true)).isTrue();
        assertThat(a.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(a.getPaymentMethod()).isEqualTo(PaymentMethod.PIX);
        assertThat(a.getPaymentConfirmedAt()).isNotNull();
        assertThat(a.getAsaasStatus()).isEqualTo("RECEIVED");

        assertThat(payments.onGatewayEvent(a.getId(), "pay_1", "CONFIRMED", true)).as("repetido").isFalse();
        assertThat(payments.onGatewayEvent(UUID.randomUUID(), "pay_1", "RECEIVED", true)).as("atendimento que nao existe").isFalse();
    }

    @Test
    @DisplayName("rota de teste do ADMIN: atendimento com cobranca nao ganha outra (409 ASAAS_CHARGE_EXISTS); sem cobranca ou inexistente passa; a cobranca fica ligada ao atendimento")
    void adminChargeIsOnePerAttendance() {
        AttendanceRepository repo = mock(AttendanceRepository.class);
        PaymentService payments = new PaymentService(repo, mock(ClientPenaltyEventRepository.class), mock(UserRepository.class));
        Attendance a = attendance();
        when(repo.findById(a.getId())).thenReturn(Optional.of(a));

        payments.assertNoGatewayCharge(a.getId()); // ainda sem cobranca
        payments.assertNoGatewayCharge(UUID.randomUUID()); // atendimento que nao existe: so a referencia, sem erro

        payments.attachGatewayCharge(a.getId(), "pay_7", "SANDBOX", "PENDING", "https://asaas/i/7");
        assertThat(a.getAsaasPaymentId()).isEqualTo("pay_7");
        assertThat(a.getAsaasMode()).isEqualTo("SANDBOX");
        assertThat(a.getAsaasInvoiceUrl()).isEqualTo("https://asaas/i/7");
        verify(repo).save(a);

        assertThatThrownBy(() -> payments.assertNoGatewayCharge(a.getId())).isInstanceOfSatisfying(AsaasException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("ASAAS_CHARGE_EXISTS");
        });
        // o webhook agora acha esta cobranca e quita o atendimento
        a.setPaymentStatus(PaymentStatus.PENDING);
        assertThat(payments.onGatewayEvent(a.getId(), "pay_7", "RECEIVED", true)).isTrue();
    }

    private Attendance attendanceOf(String proReceives, String discount) {
        Attendance a = attendance();
        org.springframework.test.util.ReflectionTestUtils.setField(a, "proReceives", proReceives == null ? null : new BigDecimal(proReceives));
        org.springframework.test.util.ReflectionTestUtils.setField(a, "discount", new BigDecimal(discount));
        BaseUser proUser = new BaseUser();
        proUser.setId(UUID.randomUUID());
        a.setProfessional(new com.ospaulos.vupth.domain.professional.model.Professional(proUser));
        return a;
    }

    @Test
    @DisplayName("fatia 1: com a conta de recebimento no mesmo modo, o que o profissional estipulou vai a carteira dele e o resto fica no app")
    void splitToProfessionalWallet() {
        asaasAnswers();
        Attendance a = attendanceOf("30.00", "0.00");
        when(paymentAccounts.walletFor(a.getProfessional().getId(), "SANDBOX")).thenReturn(Optional.of("wallet-pro"));

        gateway("k", SANDBOX, true).ensureCharge(a, new BigDecimal("32.90"));

        verify(client).createCharge("cus_1", new BigDecimal("32.90"), "PIX", a.getId().toString(), "VUPTH - atendimento",
                List.of(new AsaasClient.Split("wallet-pro", new BigDecimal("30.00"))));
    }

    @Test
    @DisplayName("fatia 1: sem conta de recebimento, com cupom, sem o valor do profissional ou com ele >= cobrado: sem divisao")
    void noSplit() {
        asaasAnswers();
        PaymentGatewayService g = gateway("k", SANDBOX, true);
        Attendance noAccount = attendanceOf("30.00", "0.00");
        when(paymentAccounts.walletFor(any(), any())).thenReturn(Optional.empty());
        assertThat(g.splitsFor(noAccount, new BigDecimal("32.90"))).isEmpty();

        when(paymentAccounts.walletFor(any(), any())).thenReturn(Optional.of("wallet-pro"));
        assertThat(g.splitsFor(attendanceOf("30.00", "5.00"), new BigDecimal("27.90"))).as("cupom: fatia 2").isEmpty();
        assertThat(g.splitsFor(attendanceOf(null, "0.00"), new BigDecimal("32.90"))).as("servico antigo").isEmpty();
        assertThat(g.splitsFor(attendanceOf("32.90", "0.00"), new BigDecimal("32.90"))).as("nada sobraria ao app").isEmpty();
        assertThat(g.splitsFor(attendanceOf("30.00", "0.00"), new BigDecimal("32.90"))).hasSize(1);
    }

    @Test
    @DisplayName("pagamento-manual-e-asaas: o Recebi manual cancela a cobranca; sem cobranca ou sem chave nada e chamado; o Asaas recusando nao quebra")
    void cancelCharge() {
        PaymentGatewayService on = gateway("chave", SANDBOX, true);
        Attendance withCharge = attendance();
        withCharge.setAsaasPaymentId("pay_1");
        withCharge.setAsaasStatus("PENDING");

        assertThat(on.cancelCharge(withCharge)).isTrue();
        verify(client).cancelCharge("pay_1");
        assertThat(withCharge.getAsaasStatus()).isEqualTo("DELETED");

        assertThat(on.cancelCharge(attendance())).isFalse();
        PaymentGatewayService noKey = gateway("", SANDBOX, true);
        assertThat(noKey.cancelCharge(withCharge)).isFalse();

        Attendance refused = attendance();
        refused.setAsaasPaymentId("pay_2");
        refused.setAsaasStatus("RECEIVED");
        org.mockito.Mockito.doThrow(new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_REJECTED", "ja recebida"))
                .when(client).cancelCharge("pay_2");
        assertThat(on.cancelCharge(refused)).isFalse();
        assertThat(refused.getAsaasStatus()).isEqualTo("RECEIVED");
    }
}
