package com.ospaulos.vupth.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests — o Pix copia-e-cola (BR Code estatico, EMV do Banco Central). */
class PixBrCodeTest {

    @Test
    @DisplayName("CRC16-CCITT confere com o valor de referencia (\"123456789\" -> 29B1)")
    void crcReference() {
        assertThat(PixBrCode.crc16("123456789")).isEqualTo("29B1");
    }

    @Test
    @DisplayName("Monta os campos na ordem do padrao, com valor, nome, cidade, txid e CRC no fim")
    void buildsPayload() {
        String code = PixBrCode.build("ana@mail.com", "Ana Júlia", "São Paulo", new BigDecimal("45"), "VUPTH-abc-123");

        assertThat(code).startsWith("000201")
                .contains("0014br.gov.bcb.pix0112ana@mail.com")
                .contains("52040000").contains("5303986")
                .contains("540545.00")
                .contains("5802BR")
                .contains("5909ANA JULIA")
                .contains("6009SAO PAULO")
                .contains("62150511VUPTHABC123");
        String body = code.substring(0, code.length() - 4);
        assertThat(body).endsWith("6304");
        assertThat(code.substring(code.length() - 4)).isEqualTo(PixBrCode.crc16(body));
    }

    @Test
    @DisplayName("Sem valor o pagador digita; sem nome, cidade e txid entram os padroes")
    void defaults() {
        String code = PixBrCode.build("+5511987654321", null, " ", null, null);

        assertThat(code).contains("53039865802BR")
                .contains("5905VUPTH")
                .contains("6006BRASIL")
                .contains("62070503***");
        assertThat(PixBrCode.build("k", "n", "c", BigDecimal.ZERO, "t"))
                .isEqualTo(PixBrCode.build("k", "n", "c", null, "t"));
    }

    @Test
    @DisplayName("Nome e cidade cortados no limite; txid ate 25")
    void limits() {
        assertThat(PixBrCode.ascii("a".repeat(30), 25, "X")).hasSize(25);
        assertThat(PixBrCode.ascii("!!!", 25, "X")).isEqualTo("X");
        assertThat(PixBrCode.txid("x".repeat(40))).hasSize(25);
        assertThat(PixBrCode.field("01", "abc")).isEqualTo("0103abc");
    }

    @Test
    @DisplayName("isOpen: pendente e aguardando ainda devem; pago nao")
    void openStatuses() {
        assertThat(PaymentStatus.PENDING.isOpen()).isTrue();
        assertThat(PaymentStatus.AWAITING_CONFIRMATION.isOpen()).isTrue();
        assertThat(PaymentStatus.PAID.isOpen()).isFalse();
    }
}
