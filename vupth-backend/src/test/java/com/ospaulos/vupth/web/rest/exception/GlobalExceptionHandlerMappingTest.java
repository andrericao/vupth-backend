package com.ospaulos.vupth.web.rest.exception;

import com.ospaulos.vupth.api.user.exception.NoFieldsToUpdateException;
import com.ospaulos.vupth.api.user.exception.ResetCodeExpiredException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyCancelledException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyInProgressException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceCancelNotAllowedException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceServiceItemNotFoundException;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.owner.exception.OwnerNotFoundException;
import com.ospaulos.vupth.domain.payment.exception.PaymentNotAvailableException;
import com.ospaulos.vupth.domain.professional.exception.NotAProfessionalException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.queue.offer.exception.SwapOfferNotFoundException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapRequestNotFoundException;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenNotFound;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GlobalExceptionHandler — status e codigo de cada handler que nenhum teste de integracao disparava
 * (cobertura-98). O contrato com os apps e o {@code code}: e por ele que a tela decide a mensagem.
 */
class GlobalExceptionHandlerMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest req = new MockHttpServletRequest("GET", "/qualquer");

    private static void assertMapped(ResponseEntity<GlobalExceptionHandler.ErrorResponse> resp,
                                     HttpStatus status, String code) {
        assertThat(resp.getStatusCode()).isEqualTo(status);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().status()).isEqualTo(status.value());
        assertThat(resp.getBody().code()).isEqualTo(code);
    }

    @Test
    void notFound() {
        assertMapped(handler.handleNoSuchElement(new NoSuchElementException("x"), req),
                HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertMapped(handler.handleSwapRequestNotFound(mock(SwapRequestNotFoundException.class), req),
                HttpStatus.NOT_FOUND, "QUEUE_SWAP_NOT_FOUND");
        assertMapped(handler.handleSwapOfferNotFound(mock(SwapOfferNotFoundException.class), req),
                HttpStatus.NOT_FOUND, "QUEUE_SWAP_OFFER_NOT_FOUND");
        assertMapped(handler.handleClientNotFound(mock(ClientNotFoundException.class), req),
                HttpStatus.NOT_FOUND, "CLIENT_NOT_FOUND");
        assertMapped(handler.handleOwnerNotFound(mock(OwnerNotFoundException.class), req),
                HttpStatus.NOT_FOUND, "OWNER_NOT_FOUND");
        assertMapped(handler.handleAttendanceServiceItemNotFound(mock(AttendanceServiceItemNotFoundException.class), req),
                HttpStatus.NOT_FOUND, "ATTENDANCE_SERVICE_ITEM_NOT_FOUND");
        assertMapped(handler.handleClientNotInQueue(mock(ClientNotInQueueException.class), req),
                HttpStatus.NOT_FOUND, "CLIENT_NOT_IN_QUEUE");
        assertMapped(handler.handleRefreshTokenNotFound(mock(RefreshTokenNotFound.class), req),
                HttpStatus.NOT_FOUND, "REFRESH_TOKEN_NOT_FOUND");
    }

    @Test
    void badRequest() {
        var photo = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(2L * 1024 * 1024), req);
        assertMapped(photo, HttpStatus.BAD_REQUEST, "INVALID_PROFILE_PHOTO");
        assertThat(photo.getBody().message()).isEqualTo("A foto passa de 2 MB.");

        assertMapped(handler.handleNoFieldsToUpdate(mock(NoFieldsToUpdateException.class), req),
                HttpStatus.BAD_REQUEST, "NO_FIELDS_TO_UPDATE");
        assertMapped(handler.handleResetCodeExpired(mock(ResetCodeExpiredException.class), req),
                HttpStatus.BAD_REQUEST, "RESET_CODE_EXPIRED");
    }

    @Test
    void unauthorized() {
        assertMapped(handler.handleDisabled(new DisabledException("x"), req),
                HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED");
        assertMapped(handler.handleLocked(new LockedException("x"), req),
                HttpStatus.UNAUTHORIZED, "ACCOUNT_LOCKED");
    }

    @Test
    void forbidden() {
        assertMapped(handler.handleProfessionalNotAffiliated(mock(ProfessionalNotAffiliatedException.class), req),
                HttpStatus.FORBIDDEN, "PROFESSIONAL_NOT_AFFILIATED");
        assertMapped(handler.handleNotAProfessional(mock(NotAProfessionalException.class), req),
                HttpStatus.FORBIDDEN, "NOT_A_PROFESSIONAL");
        assertMapped(handler.handleCancelNotAllowed(mock(AttendanceCancelNotAllowedException.class), req),
                HttpStatus.FORBIDDEN, "ATTENDANCE_CANCEL_NOT_ALLOWED");
    }

    @Test
    void conflict() {
        assertMapped(handler.handleAlreadyInProgress(mock(AttendanceAlreadyInProgressException.class), req),
                HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_IN_PROGRESS");
        assertMapped(handler.handleAlreadyCancelled(mock(AttendanceAlreadyCancelledException.class), req),
                HttpStatus.CONFLICT, "ATTENDANCE_ALREADY_CANCELLED");
        assertMapped(handler.handlePaymentNotAvailable(mock(PaymentNotAvailableException.class), req),
                HttpStatus.CONFLICT, "PAYMENT_NOT_AVAILABLE");
    }
}
