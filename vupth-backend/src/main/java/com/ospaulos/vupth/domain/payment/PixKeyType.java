package com.ospaulos.vupth.domain.payment;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Tipos de chave Pix (Banco Central) e a normalizacao de cada um. {@link #normalize} devolve vazio
 * quando a chave nao bate com o tipo — quem chama responde 422 {@code PIX_KEY_INVALID}.
 */
public enum PixKeyType {
    CPF, CNPJ, EMAIL, PHONE, RANDOM;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern RANDOM_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** A chave no formato que vai ao BR Code, ou vazio se ela nao bate com o tipo. */
    public Optional<String> normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim();
        return switch (this) {
            case CPF -> digits(key, 11).filter(PixKeyType::validCpf);
            case CNPJ -> digits(key, 14).filter(PixKeyType::validCnpj);
            case EMAIL -> {
                String email = key.toLowerCase(Locale.ROOT);
                yield email.length() <= 77 && EMAIL_PATTERN.matcher(email).matches()
                        ? Optional.of(email) : Optional.empty();
            }
            case PHONE -> phone(key);
            case RANDOM -> {
                String random = key.toLowerCase(Locale.ROOT);
                yield RANDOM_PATTERN.matcher(random).matches() ? Optional.of(random) : Optional.empty();
            }
        };
    }

    private static Optional<String> digits(String key, int length) {
        if (!key.matches("[0-9.\\-/ ]+")) {
            return Optional.empty();
        }
        String d = key.replaceAll("\\D", "");
        return d.length() == length ? Optional.of(d) : Optional.empty();
    }

    /** Telefone no formato do Pix: +55, DDD e o numero (10 ou 11 digitos depois do 55). */
    private static Optional<String> phone(String key) {
        if (!key.matches("[0-9+()\\- ]+")) {
            return Optional.empty();
        }
        String d = key.replaceAll("\\D", "");
        if (d.startsWith("55") && (d.length() == 12 || d.length() == 13)) {
            return Optional.of("+" + d);
        }
        if (!key.startsWith("+") && (d.length() == 10 || d.length() == 11)) {
            return Optional.of("+55" + d);
        }
        return Optional.empty();
    }

    static boolean validCpf(String d) {
        if (d.chars().distinct().count() == 1) {
            return false;
        }
        return checkDigit(d, 9, 10) == d.charAt(9) - '0' && checkDigit(d, 10, 11) == d.charAt(10) - '0';
    }

    private static int checkDigit(String d, int length, int firstWeight) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (d.charAt(i) - '0') * (firstWeight - i);
        }
        int rest = (sum * 10) % 11;
        return rest == 10 ? 0 : rest;
    }

    static boolean validCnpj(String d) {
        if (d.chars().distinct().count() == 1) {
            return false;
        }
        int[] w1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] w2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        return cnpjDigit(d, w1) == d.charAt(12) - '0' && cnpjDigit(d, w2) == d.charAt(13) - '0';
    }

    private static int cnpjDigit(String d, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (d.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}
