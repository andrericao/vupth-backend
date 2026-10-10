package com.ospaulos.vupth.domain.establishment.transfer;

import org.springframework.http.HttpStatus;

/**
 * Recusas da passagem de posse, cada uma com o status e o codigo do contrato (desativar-conta):
 * 422 TRANSFER_TARGET_NOT_IN_TEAM, 409 TRANSFER_ALREADY_PENDING, 404 OWNERSHIP_TRANSFER_NOT_FOUND.
 */
public class OwnershipTransferException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private OwnershipTransferException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static OwnershipTransferException notInTeam() {
        return new OwnershipTransferException(HttpStatus.UNPROCESSABLE_ENTITY, "TRANSFER_TARGET_NOT_IN_TEAM",
                "The new owner must be an active professional of this establishment.");
    }

    public static OwnershipTransferException alreadyPending() {
        return new OwnershipTransferException(HttpStatus.CONFLICT, "TRANSFER_ALREADY_PENDING",
                "There is already a pending ownership transfer for this establishment.");
    }

    public static OwnershipTransferException notFound() {
        return new OwnershipTransferException(HttpStatus.NOT_FOUND, "OWNERSHIP_TRANSFER_NOT_FOUND",
                "No pending ownership transfer.");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
