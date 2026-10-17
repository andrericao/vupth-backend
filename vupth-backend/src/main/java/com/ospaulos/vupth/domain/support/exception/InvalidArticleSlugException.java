package com.ospaulos.vupth.domain.support.exception;

/** O slug do artigo fora do formato (a-z, 0-9 e hifen, ate 80). Vira {@code 422 INVALID_ARTICLE_SLUG}. */
public class InvalidArticleSlugException extends RuntimeException {

    public InvalidArticleSlugException() {
        super("O artigo deve ter de 1 a 80 caracteres: letras minusculas, numeros e hifen.");
    }
}
