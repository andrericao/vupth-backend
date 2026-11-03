package com.ospaulos.vupth.domain.feedback.exception;

/** O usuario passou do teto de feedbacks do dia — vira 429 FEEDBACK_LIMIT. */
public class FeedbackLimitException extends RuntimeException {
    public FeedbackLimitException() {
        super("Limite diario de feedbacks atingido, tente amanha");
    }
}
