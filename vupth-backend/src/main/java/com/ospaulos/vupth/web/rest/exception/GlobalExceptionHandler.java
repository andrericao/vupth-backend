package com.ospaulos.vupth.web.rest.exception;

import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAccessDeniedException;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationAlreadyPendingException;
import com.ospaulos.vupth.domain.affiliation.exception.AffiliationNotFoundException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyCancelledException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyRatedException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceCancelNotAllowedException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceAlreadyInProgressException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceInvalidStatusException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceNotFoundException;
import com.ospaulos.vupth.domain.attendance.exception.AttendanceServiceItemNotFoundException;
import com.ospaulos.vupth.domain.attendance.exception.ServiceItemNotOfProfessionalException;
import com.ospaulos.vupth.domain.attendance.exception.TargetTimeNotFeasibleException;
import com.ospaulos.vupth.domain.client.exception.ClientNotFoundException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapAlreadyPendingException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapNotAllowedException;
import com.ospaulos.vupth.domain.queue.swap.exception.SwapRequestNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentAccessDeniedException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHasActiveAttendancesException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotOpenException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotFoundException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentHoursRequiredException;
import com.ospaulos.vupth.domain.establishment.exception.EstablishmentNotReadyException;
import com.ospaulos.vupth.domain.interval.exception.IntervalConflictException;
import com.ospaulos.vupth.domain.interval.exception.IntervalNotFoundException;
import com.ospaulos.vupth.domain.interval.exception.InvalidIntervalTimeException;
import com.ospaulos.vupth.domain.owner.exception.OwnerNotFoundException;
import com.ospaulos.vupth.domain.professional.exception.NotAProfessionalException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalAlreadyAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalHasActiveAttendancesException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotAffiliatedException;
import com.ospaulos.vupth.domain.professional.exception.ProfessionalNotFoundException;
import com.ospaulos.vupth.domain.queue.exception.ClientNotInQueueException;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenInvalidException;
import com.ospaulos.vupth.domain.refreshToken.exception.RefreshTokenNotFound;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemAlreadyExistsException;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceItemNotFoundException;
import com.ospaulos.vupth.domain.serviceItem.exception.ServiceTypeKeywordAlreadyExistsException;
import com.ospaulos.vupth.api.professional.exception.UserAlreadyProfessionalException;
import com.ospaulos.vupth.api.user.exception.EmailNotAvailableException;
import com.ospaulos.vupth.api.user.exception.InvalidBirthDateException;
import com.ospaulos.vupth.api.user.exception.EmailsAreTheSameException;
import com.ospaulos.vupth.api.user.exception.UserNotFoundException;
import com.ospaulos.vupth.api.user.exception.NoFieldsToUpdateException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Centralizes application exception handling.
 *
 * Every handled exception is logged with:
 *   [STATUS] ExceptionType | method=HTTP_METHOD path=REQUEST_URI | detail
 *
 * HTTP status convention:
 *   400 — Invalid data or business rule violation (invalid state)
 *   401 — Unauthenticated (wrong credentials, invalid/expired token)
 *   403 — Authenticated but not authorized for the resource
 *   404 — Resource not found
 *   409 — Conflict (duplicate, incompatible state)
 *   422 — Unprocessable entity (field validation failure)
 *   500 — Unexpected internal error
 *
 * @since v7
 * @compat ProfessionalAlreadyAffiliatedException added v14
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ─────────────────────────────────────────────
    // 404 NOT FOUND
    // ─────────────────────────────────────────────

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(
            EntityNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] EntityNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "NOT_FOUND");
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElement(
            NoSuchElementException ex, HttpServletRequest req) {
        log.warn("[404] NoSuchElement | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "Resource not found", "NOT_FOUND");
    }

    @ExceptionHandler(AttendanceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAttendanceNotFound(
            AttendanceNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] AttendanceNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "ATTENDANCE_NOT_FOUND");
    }

    @ExceptionHandler(EstablishmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEstablishmentNotFound(
            EstablishmentNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] EstablishmentNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "ESTABLISHMENT_NOT_FOUND");
    }

    @ExceptionHandler(EstablishmentNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleEstablishmentNotReady(
            EstablishmentNotReadyException ex, HttpServletRequest req) {
        log.warn("[422] EstablishmentNotReady | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "ESTABLISHMENT_NOT_READY");
    }

    @ExceptionHandler(EstablishmentHoursRequiredException.class)
    public ResponseEntity<ErrorResponse> handleEstablishmentHoursRequired(
            EstablishmentHoursRequiredException ex, HttpServletRequest req) {
        log.warn("[422] EstablishmentHoursRequired | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "ESTABLISHMENT_HOURS_REQUIRED");
    }

    @ExceptionHandler(TargetTimeNotFeasibleException.class)
    public ResponseEntity<ErrorResponse> handleTargetTimeNotFeasible(
            TargetTimeNotFeasibleException ex, HttpServletRequest req) {
        log.warn("[422] TargetTimeNotFeasible | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "QUEUE_TARGET_NOT_FEASIBLE");
    }

    @ExceptionHandler(ServiceItemNotOfProfessionalException.class)
    public ResponseEntity<ErrorResponse> handleServiceItemNotOfProfessional(
            ServiceItemNotOfProfessionalException ex, HttpServletRequest req) {
        log.warn("[422] ServiceItemNotOfProfessional | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "SERVICE_ITEM_NOT_OF_PROFESSIONAL");
    }

    @ExceptionHandler(SwapNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleSwapNotAllowed(
            SwapNotAllowedException ex, HttpServletRequest req) {
        log.warn("[422] SwapNotAllowed | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage(), "QUEUE_SWAP_NOT_ALLOWED");
    }

    @ExceptionHandler(SwapRequestNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSwapRequestNotFound(
            SwapRequestNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] SwapRequestNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "QUEUE_SWAP_NOT_FOUND");
    }

    @ExceptionHandler(ProfessionalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProfessionalNotFound(
            ProfessionalNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] ProfessionalNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "PROFESSIONAL_NOT_FOUND");
    }

    @ExceptionHandler(ClientNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleClientNotFound(
            ClientNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] ClientNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "CLIENT_NOT_FOUND");
    }

    @ExceptionHandler(OwnerNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOwnerNotFound(
            OwnerNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] OwnerNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "OWNER_NOT_FOUND");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(
            UserNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] UserNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "USER_NOT_FOUND");
    }

    @ExceptionHandler(ServiceItemNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleServiceItemNotFound(
            ServiceItemNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] ServiceItemNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "SERVICE_ITEM_NOT_FOUND");
    }

    @ExceptionHandler(AttendanceServiceItemNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAttendanceServiceItemNotFound(
            AttendanceServiceItemNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] AttendanceServiceItemNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "ATTENDANCE_SERVICE_ITEM_NOT_FOUND");
    }

    @ExceptionHandler(AffiliationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAffiliationNotFound(
            AffiliationNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] AffiliationNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "AFFILIATION_NOT_FOUND");
    }

    @ExceptionHandler(ClientNotInQueueException.class)
    public ResponseEntity<ErrorResponse> handleClientNotInQueue(
            ClientNotInQueueException ex, HttpServletRequest req) {
        log.warn("[404] ClientNotInQueue | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "CLIENT_NOT_IN_QUEUE");
    }

    @ExceptionHandler(RefreshTokenNotFound.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenNotFound(
            RefreshTokenNotFound ex, HttpServletRequest req) {
        log.warn("[404] RefreshTokenNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "REFRESH_TOKEN_NOT_FOUND");
    }

    // ─────────────────────────────────────────────
    // 400 BAD REQUEST — business rule / invalid state
    // ─────────────────────────────────────────────

    @ExceptionHandler(AttendanceInvalidStatusException.class)
    public ResponseEntity<ErrorResponse> handleInvalidStatus(
            AttendanceInvalidStatusException ex, HttpServletRequest req) {
        log.warn("[400] AttendanceInvalidStatus | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), "ATTENDANCE_INVALID_STATUS");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("[400] IllegalArgument | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_ARGUMENT");
    }

    @ExceptionHandler(InvalidIntervalTimeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIntervalTime(
            InvalidIntervalTimeException ex, HttpServletRequest req) {
        log.warn("[400] InvalidIntervalTime | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), "INTERVAL_INVALID_TIME");
    }

    @ExceptionHandler(IntervalNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleIntervalNotFound(
            IntervalNotFoundException ex, HttpServletRequest req) {
        log.warn("[404] IntervalNotFound | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), "INTERVAL_NOT_FOUND");
    }

    @ExceptionHandler(EmailsAreTheSameException.class)
    public ResponseEntity<ErrorResponse> handleEmailsSame(
            EmailsAreTheSameException ex, HttpServletRequest req) {
        log.warn("[400] EmailsAreTheSame | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), "EMAILS_ARE_THE_SAME");
    }

    @ExceptionHandler(InvalidBirthDateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBirthDate(
            InvalidBirthDateException ex, HttpServletRequest req) {
        log.warn("[400] InvalidBirthDate | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_BIRTH_DATE");
    }

    @ExceptionHandler(NoFieldsToUpdateException.class)
    public ResponseEntity<ErrorResponse> handleNoFieldsToUpdate(
            NoFieldsToUpdateException ex, HttpServletRequest req) {
        log.warn("[400] NoFieldsToUpdate | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), "NO_FIELDS_TO_UPDATE");
    }

    // ─────────────────────────────────────────────
    // 401 UNAUTHORIZED
    // ─────────────────────────────────────────────

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(
            BadCredentialsException ex, HttpServletRequest req) {
        log.warn("[401] BadCredentials | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "Invalid email or password", "BAD_CREDENTIALS");
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabled(
            DisabledException ex, HttpServletRequest req) {
        log.warn("[401] AccountDisabled | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "Account is disabled", "ACCOUNT_DISABLED");
    }

    @ExceptionHandler(LockedException.class)
    public ResponseEntity<ErrorResponse> handleLocked(
            LockedException ex, HttpServletRequest req) {
        log.warn("[401] AccountLocked | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, "Account is locked", "ACCOUNT_LOCKED");
    }

    @ExceptionHandler(RefreshTokenInvalidException.class)
    public ResponseEntity<ErrorResponse> handleRefreshTokenInvalid(
            RefreshTokenInvalidException ex, HttpServletRequest req) {
        log.warn("[401] RefreshTokenInvalid | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), "REFRESH_TOKEN_INVALID");
    }

    // ─────────────────────────────────────────────
    // 403 FORBIDDEN
    // ─────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req) {
        log.warn("[403] AccessDenied | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, "Access denied", "ACCESS_DENIED");
    }

    @ExceptionHandler(AffiliationAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAffiliationAccessDenied(
            AffiliationAccessDeniedException ex, HttpServletRequest req) {
        log.warn("[403] AffiliationAccessDenied | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), "AFFILIATION_ACCESS_DENIED");
    }

    @ExceptionHandler(EstablishmentAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleEstablishmentAccess(
            EstablishmentAccessDeniedException ex, HttpServletRequest req) {
        log.warn("[403] EstablishmentAccessDenied | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), "ESTABLISHMENT_ACCESS_DENIED");
    }

    @ExceptionHandler(ProfessionalNotAffiliatedException.class)
    public ResponseEntity<ErrorResponse> handleProfessionalNotAffiliated(
            ProfessionalNotAffiliatedException ex, HttpServletRequest req) {
        log.warn("[403] ProfessionalNotAffiliated | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), "PROFESSIONAL_NOT_AFFILIATED");
    }

    @ExceptionHandler(NotAProfessionalException.class)
    public ResponseEntity<ErrorResponse> handleNotAProfessional(
            NotAProfessionalException ex, HttpServletRequest req) {
        log.warn("[403] NotAProfessional | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), "NOT_A_PROFESSIONAL");
    }

    // ─────────────────────────────────────────────
    // 409 CONFLICT
    // ─────────────────────────────────────────────

    @ExceptionHandler(EmailNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotAvailable(
            EmailNotAvailableException ex, HttpServletRequest req) {
        log.warn("[409] EmailNotAvailable | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "EMAIL_NOT_AVAILABLE");
    }

    @ExceptionHandler(SwapAlreadyPendingException.class)
    public ResponseEntity<ErrorResponse> handleSwapAlreadyPending(
            SwapAlreadyPendingException ex, HttpServletRequest req) {
        log.warn("[409] SwapAlreadyPending | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "QUEUE_SWAP_ALREADY_PENDING");
    }

    @ExceptionHandler(UserAlreadyProfessionalException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyProfessional(
            UserAlreadyProfessionalException ex, HttpServletRequest req) {
        log.warn("[409] UserAlreadyProfessional | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "USER_ALREADY_PROFESSIONAL");
    }

    @ExceptionHandler(AffiliationAlreadyPendingException.class)
    public ResponseEntity<ErrorResponse> handleAffiliationAlreadyPending(
            AffiliationAlreadyPendingException ex, HttpServletRequest req) {
        log.warn("[409] AffiliationAlreadyPending | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "AFFILIATION_ALREADY_PENDING");
    }

    @ExceptionHandler(ProfessionalAlreadyAffiliatedException.class)
    public ResponseEntity<ErrorResponse> handleProfessionalAlreadyAffiliated(
            ProfessionalAlreadyAffiliatedException ex, HttpServletRequest req) {
        log.warn("[409] ProfessionalAlreadyAffiliated | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "PROFESSIONAL_ALREADY_AFFILIATED");
    }

    @ExceptionHandler(AttendanceAlreadyInProgressException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyInProgress(
            AttendanceAlreadyInProgressException ex, HttpServletRequest req) {
        log.warn("[409] AttendanceAlreadyInProgress | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "ATTENDANCE_ALREADY_IN_PROGRESS");
    }

    @ExceptionHandler(AttendanceAlreadyCancelledException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyCancelled(
            AttendanceAlreadyCancelledException ex, HttpServletRequest req) {
        log.warn("[409] AttendanceAlreadyCancelled | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "ATTENDANCE_ALREADY_CANCELLED");
    }

    @ExceptionHandler(AttendanceCancelNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleCancelNotAllowed(
            AttendanceCancelNotAllowedException ex, HttpServletRequest req) {
        log.warn("[403] AttendanceCancelNotAllowed | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), "ATTENDANCE_CANCEL_NOT_ALLOWED");
    }

    @ExceptionHandler(AttendanceAlreadyRatedException.class)
    public ResponseEntity<ErrorResponse> handleAlreadyRated(
            AttendanceAlreadyRatedException ex, HttpServletRequest req) {
        log.warn("[409] AttendanceAlreadyRated | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "ATTENDANCE_ALREADY_RATED");
    }

    @ExceptionHandler(ServiceItemAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleServiceItemExists(
            ServiceItemAlreadyExistsException ex, HttpServletRequest req) {
        log.warn("[409] ServiceItemAlreadyExists | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "SERVICE_ITEM_ALREADY_EXISTS");
    }

    @ExceptionHandler(ServiceTypeKeywordAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleServiceTypeKeywordExists(
            ServiceTypeKeywordAlreadyExistsException ex, HttpServletRequest req) {
        log.warn("[409] ServiceTypeKeywordAlreadyExists | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "SERVICE_TYPE_KEYWORD_ALREADY_EXISTS");
    }

    @ExceptionHandler(ProfessionalHasActiveAttendancesException.class)
    public ResponseEntity<ErrorResponse> handleProfessionalHasActiveAttendances(
            ProfessionalHasActiveAttendancesException ex, HttpServletRequest req) {
        log.warn("[409] ProfessionalHasActiveAttendances | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "PROFESSIONAL_HAS_ACTIVE_ATTENDANCES");
    }

    @ExceptionHandler(IntervalConflictException.class)
    public ResponseEntity<ErrorResponse> handleIntervalConflict(
            IntervalConflictException ex, HttpServletRequest req) {
        log.warn("[409] IntervalConflict | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "INTERVAL_CONFLICT");
    }

    @ExceptionHandler(EstablishmentHasActiveAttendancesException.class)
    public ResponseEntity<ErrorResponse> handleEstablishmentHasActiveAttendances(
            EstablishmentHasActiveAttendancesException ex, HttpServletRequest req) {
        log.warn("[409] EstablishmentHasActiveAttendances | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "ESTABLISHMENT_HAS_ACTIVE_ATTENDANCES");
    }

    @ExceptionHandler(EstablishmentNotOpenException.class)
    public ResponseEntity<ErrorResponse> handleEstablishmentNotOpen(
            EstablishmentNotOpenException ex, HttpServletRequest req) {
        log.warn("[409] EstablishmentNotOpen | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, ex.getMessage(), "ESTABLISHMENT_NOT_OPEN");
    }

    // ─────────────────────────────────────────────
    // 422 UNPROCESSABLE ENTITY — field validation
    // ─────────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        log.warn("[422] ValidationError | {} {} | fields={}", req.getMethod(), req.getRequestURI(), fieldErrors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ValidationErrorResponse(
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "Field validation error",
                        "VALIDATION_ERROR",
                        fieldErrors,
                        OffsetDateTime.now()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest req) {
        log.warn("[400] HttpMessageNotReadable | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST,
                "Invalid data format in request body. Check field types and values.",
                "INVALID_REQUEST_BODY");
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex,
            HttpServletRequest req) {
        String detail = String.format("Invalid value for parameter '%s'", ex.getName());
        log.warn("[400] TypeMismatch | {} {} | {}", req.getMethod(), req.getRequestURI(), detail);
        return build(HttpStatus.BAD_REQUEST, detail, "INVALID_PATH_VARIABLE");
    }

    // ─────────────────────────────────────────────
    // 500 INTERNAL SERVER ERROR — fallback
    // ─────────────────────────────────────────────

    // 405 METHOD NOT ALLOWED — metodo HTTP errado para a rota (ex.: PATCH numa rota DELETE).
    // Sem este handler cairia no handleGeneric e viraria um 500 enganoso.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        log.warn("[405] MethodNotSupported | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), "METHOD_NOT_ALLOWED");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("[500] UnexpectedError | {} {} | {}", req.getMethod(), req.getRequestURI(), ex.getMessage(), ex);
        String debugMsg = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return build(HttpStatus.INTERNAL_SERVER_ERROR, debugMsg, "INTERNAL_ERROR");
    }

    // ─────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String code) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), message, code, OffsetDateTime.now()));
    }

    public record ErrorResponse(
            int status,
            String message,
            String code,
            OffsetDateTime timestamp) {
    }

    public record ValidationErrorResponse(
            int status,
            String message,
            String code,
            Map<String, String> fieldErrors,
            OffsetDateTime timestamp) {
    }
}
