package com.ospaulos.vupth.unit.crypto;

import com.ospaulos.vupth.infrastructure.crypto.DataCipher;
import com.ospaulos.vupth.infrastructure.crypto.EncryptedStringConverter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S4 (DEC-81): AES-256-GCM nos dados pessoais; texto puro antigo segue legivel; sem chave, nada muda. */
class DataCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final DataCipher cipher = new DataCipher(KEY, true);

    @Test
    @DisplayName("cifra com o prefixo, decifra de volta e nao repete o texto cifrado (IV novo)")
    void roundTrip() {
        String sealed = cipher.encrypt("+5511987654321");

        assertThat(sealed).startsWith("enc:v1:").doesNotContain("5511987654321");
        assertThat(cipher.decrypt(sealed)).isEqualTo("+5511987654321");
        assertThat(cipher.encrypt("+5511987654321")).isNotEqualTo(sealed);
        assertThat(cipher.decrypt(cipher.encrypt("José da Conceição ✓"))).isEqualTo("José da Conceição ✓");
    }

    @Test
    @DisplayName("nulo fica nulo; ja cifrado nao cifra de novo; texto puro antigo e lido como esta")
    void edges() {
        String sealed = cipher.encrypt("abc");
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
        assertThat(cipher.encrypt(sealed)).isEqualTo(sealed);
        assertThat(cipher.decrypt("texto antigo")).isEqualTo("texto antigo");
        assertThat(DataCipher.isEncrypted(sealed)).isTrue();
        assertThat(DataCipher.isEncrypted("abc")).isFalse();
        assertThat(cipher.enabled()).isTrue();
    }

    @Test
    @DisplayName("chave trocada ou dado adulterado: erro claro, nunca lixo")
    void wrongKeyOrTampered() {
        String sealed = cipher.encrypt("segredo");
        DataCipher other = new DataCipher(OTHER_KEY, true);

        assertThatThrownBy(() -> other.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("enc:v1:%%%")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("sem chave: segue em texto puro; dado cifrado sem chave nao abre; obrigatoria sem chave nao sobe")
    void withoutKey() {
        DataCipher none = new DataCipher("", false);
        assertThat(none.enabled()).isFalse();
        assertThat(none.encrypt("abc")).isEqualTo("abc");
        assertThatThrownBy(() -> none.decrypt(cipher.encrypt("abc"))).isInstanceOf(IllegalStateException.class);
        assertThat(new DataCipher(null, false).enabled()).isFalse();
        assertThatThrownBy(() -> new DataCipher(" ", true)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATA_ENCRYPTION_KEY");
    }

    @Test
    @DisplayName("chave que nao e base64 ou nao tem 32 bytes nao sobe")
    void badKey() {
        assertThatThrownBy(() -> new DataCipher("n@o-e-base64!", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DataCipher(Base64.getEncoder().encodeToString(new byte[16]), true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("o conversor do JPA grava cifrado e le decifrado")
    void converter() {
        var converter = new EncryptedStringConverter(cipher);
        String column = converter.convertToDatabaseColumn("chave@pix.com");
        assertThat(column).startsWith("enc:v1:");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo("chave@pix.com");
    }
}
