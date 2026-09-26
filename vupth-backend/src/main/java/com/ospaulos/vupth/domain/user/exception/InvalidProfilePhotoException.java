package com.ospaulos.vupth.domain.user.exception;

/** Foto recusada: vazia, grande demais ou de um tipo que nao e imagem. HTTP 400 INVALID_PROFILE_PHOTO. */
public class InvalidProfilePhotoException extends RuntimeException {
    public InvalidProfilePhotoException(String message) {
        super(message);
    }
}
