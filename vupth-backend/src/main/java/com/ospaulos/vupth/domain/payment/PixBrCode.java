package com.ospaulos.vupth.domain.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Monta o Pix copia-e-cola (BR Code estatico, padrao EMV do Banco Central), sem gateway: a chave do
 * profissional, o valor e um codigo do atendimento. O app mostra o texto e desenha o QR dele.
 */
public final class PixBrCode {

    private static final String GUI = "br.gov.bcb.pix";

    private PixBrCode() {
    }

    /**
     * @param key        chave ja normalizada ({@link PixKeyType#normalize})
     * @param holderName nome que aparece no app do banco (ate 25 letras; acento sai)
     * @param city       cidade do recebedor (ate 15 letras; vazia vira BRASIL)
     * @param amount     valor; nulo ou zero = o pagador digita
     * @param txid       codigo do atendimento (so letras e numeros, ate 25)
     */
    public static String build(String key, String holderName, String city, BigDecimal amount, String txid) {
        StringBuilder payload = new StringBuilder();
        payload.append(field("00", "01"));
        payload.append(field("26", field("00", GUI) + field("01", key)));
        payload.append(field("52", "0000"));
        payload.append(field("53", "986"));
        if (amount != null && amount.signum() > 0) {
            payload.append(field("54", amount.setScale(2, RoundingMode.HALF_UP).toPlainString()));
        }
        payload.append(field("58", "BR"));
        payload.append(field("59", ascii(holderName, 25, "VUPTH")));
        payload.append(field("60", ascii(city, 15, "BRASIL")));
        payload.append(field("62", field("05", txid(txid))));
        payload.append("6304");
        return payload + crc16(payload.toString());
    }

    static String field(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    /** Sem acento, maiusculo, so o que o padrao aceita, cortado no limite. */
    static String ascii(String value, int max, String fallback) {
        String clean = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^A-Za-z0-9 ]", "")
                .trim()
                .toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            clean = fallback;
        }
        return clean.length() > max ? clean.substring(0, max).trim() : clean;
    }

    static String txid(String raw) {
        String clean = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (clean.isEmpty()) {
            return "***";
        }
        return clean.length() > 25 ? clean.substring(0, 25) : clean;
    }

    /** CRC16-CCITT (polinomio 0x1021, inicio 0xFFFF), em 4 hexadecimais maiusculos. */
    static String crc16(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
