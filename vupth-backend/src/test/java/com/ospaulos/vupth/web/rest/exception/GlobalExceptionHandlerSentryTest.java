package com.ospaulos.vupth.web.rest.exception;

import io.sentry.Sentry;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * BAK-2 · sentry: o 500 do {@code handleGeneric} e tratado pelo advice, entao nao chega ao
 * {@code SentryExceptionResolver} — o handler manda a excecao ao Sentry a mao. Os 4xx de negocio
 * nao vao (nao sao defeito).
 */
class GlobalExceptionHandlerSentryTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleGeneric_mandaAExcecaoAoSentry_eResponde500() {
        RuntimeException ex = new IllegalStateException("quebrou");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/qualquer");

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp = handler.handleGeneric(ex, req);

            sentry.verify(() -> Sentry.captureException(ex));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(resp.getBody().code()).isEqualTo("INTERNAL_ERROR");
        }
    }

    @Test
    void handleMethodNotSupported_naoVaiAoSentry() {
        MockHttpServletRequest req = new MockHttpServletRequest("PATCH", "/qualquer");

        try (MockedStatic<Sentry> sentry = mockStatic(Sentry.class)) {
            handler.handleMethodNotSupported(
                    new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH"), req);

            sentry.verify(() -> Sentry.captureException(org.mockito.ArgumentMatchers.any(Throwable.class)), never());
        }
    }
}
