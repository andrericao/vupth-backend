package com.ospaulos.vupth.domain.admin.exception;

import com.ospaulos.vupth.domain.admin.model.AdminPermission;

/** O usuario do painel nao tem a permissao da rota. Vira {@code 403 ADMIN_PERMISSION_DENIED}. */
public class AdminPermissionDeniedException extends RuntimeException {

    private final AdminPermission required;

    public AdminPermissionDeniedException(AdminPermission required) {
        super(required == null
                ? "Esta area do painel e so do administrador principal."
                : "Voce nao tem permissao para esta area do painel (" + required + ").");
        this.required = required;
    }

    public AdminPermission getRequired() {
        return required;
    }
}
