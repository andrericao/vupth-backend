package com.ospaulos.vupth.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests — os 5 tipos de chave Pix e a normalizacao (pagamento-pix). */
class PixKeyTypeTest {

    @Test
    @DisplayName("CPF: com ou sem mascara, so com digito verificador certo")
    void cpf() {
        assertThat(PixKeyType.CPF.normalize("529.982.247-25")).contains("52998224725");
        assertThat(PixKeyType.CPF.normalize("52998224725")).contains("52998224725");
        assertThat(PixKeyType.CPF.normalize("529.982.247-26")).isEmpty();
        assertThat(PixKeyType.CPF.normalize("52998224715")).isEmpty();
        assertThat(PixKeyType.CPF.normalize("111.111.111-11")).isEmpty();
        assertThat(PixKeyType.CPF.normalize("5299822472")).isEmpty();
        assertThat(PixKeyType.CPF.normalize("abc")).isEmpty();
    }

    @Test
    @DisplayName("CNPJ: com ou sem mascara, so com digito verificador certo")
    void cnpj() {
        assertThat(PixKeyType.CNPJ.normalize("11.222.333/0001-81")).contains("11222333000181");
        assertThat(PixKeyType.CNPJ.normalize("11.222.333/0001-82")).isEmpty();
        assertThat(PixKeyType.CNPJ.normalize("11222333000191")).isEmpty();
        assertThat(PixKeyType.CNPJ.normalize("00000000000000")).isEmpty();
        assertThat(PixKeyType.CNPJ.normalize("1122233300018")).isEmpty();
    }

    @Test
    @DisplayName("E-mail: minusculo, com @ e dominio, ate 77")
    void email() {
        assertThat(PixKeyType.EMAIL.normalize(" Ana@Mail.com ")).contains("ana@mail.com");
        assertThat(PixKeyType.EMAIL.normalize("ana@mail")).isEmpty();
        assertThat(PixKeyType.EMAIL.normalize("a".repeat(70) + "@mail.com")).isEmpty();
    }

    @Test
    @DisplayName("Telefone: +55, DDD e numero; sem +55 ganha o 55")
    void phone() {
        assertThat(PixKeyType.PHONE.normalize("+55 (11) 98765-4321")).contains("+5511987654321");
        assertThat(PixKeyType.PHONE.normalize("5511987654321")).contains("+5511987654321");
        assertThat(PixKeyType.PHONE.normalize("(11) 98765-4321")).contains("+5511987654321");
        assertThat(PixKeyType.PHONE.normalize("1133334444")).contains("+551133334444");
        assertThat(PixKeyType.PHONE.normalize("+1 202 555 0100")).isEmpty();
        assertThat(PixKeyType.PHONE.normalize("987654")).isEmpty();
        assertThat(PixKeyType.PHONE.normalize("11 9876x4321")).isEmpty();
    }

    @Test
    @DisplayName("Aleatoria: o UUID do banco, minusculo")
    void random() {
        assertThat(PixKeyType.RANDOM.normalize("123E4567-E89B-12D3-A456-426614174000"))
                .contains("123e4567-e89b-12d3-a456-426614174000");
        assertThat(PixKeyType.RANDOM.normalize("123e4567e89b12d3a456426614174000")).isEmpty();
    }

    @Test
    @DisplayName("Vazia ou nula nao vale em tipo nenhum")
    void blank() {
        for (PixKeyType type : PixKeyType.values()) {
            assertThat(type.normalize(null)).isEmpty();
            assertThat(type.normalize("  ")).isEmpty();
        }
    }
}
