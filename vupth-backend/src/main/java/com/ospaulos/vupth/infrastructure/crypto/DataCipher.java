package com.ospaulos.vupth.infrastructure.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Criptografia de campo dos dados pessoais (S4, DEC-81): AES-256-GCM, com um IV novo a cada gravacao. O valor
 * gravado e {@code enc:v1:} + base64(IV + texto cifrado + etiqueta). A chave e a variavel
 * {@code DATA_ENCRYPTION_KEY} (32 bytes em base64), nunca o Git; <b>perder a chave perde os dados</b>.
 *
 * <p>Sem chave configurada o campo segue em texto puro (dev e teste); em staging e producao
 * {@code vupth.security.data-encryption.required=true} faz o boot falhar sem a chave, em vez de gravar em claro
 * sem ninguem notar. Valor sem o prefixo e lido como esta (texto puro de antes da migracao).</p>
 */
@Component
public class DataCipher {

    public static final String PREFIX = "enc:v1:";

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public DataCipher(@Value("${vupth.security.data-encryption.key:}") String base64Key,
                      @Value("${vupth.security.data-encryption.required:false}") boolean required) {
        if (base64Key == null || base64Key.isBlank()) {
            if (required) {
                throw new IllegalStateException(
                        "DATA_ENCRYPTION_KEY ausente: os dados pessoais nao podem ser gravados em texto puro (S4).");
            }
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("DATA_ENCRYPTION_KEY nao e base64 valido.", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("DATA_ENCRYPTION_KEY deve ter 32 bytes (AES-256); veio " + raw.length + ".");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    /** Ha chave: os campos sao gravados cifrados. */
    public boolean enabled() {
        return key != null;
    }

    /** Ja esta no formato cifrado. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /** Cifra o texto; nulo fica nulo, e sem chave (ou ja cifrado) devolve como veio. */
    public String encrypt(String plain) {
        if (plain == null || key == null || isEncrypted(plain)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar o dado pessoal", e);
        }
    }

    /** Decifra; valor sem o prefixo (texto puro antigo) e nulo voltam como vieram. */
    public String decrypt(String stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("Dado cifrado no banco, mas DATA_ENCRYPTION_KEY nao esta configurada.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Falha ao decifrar o dado pessoal (chave trocada ou dado corrompido)", e);
        }
    }
}
