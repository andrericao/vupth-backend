package com.ospaulos.vupth.domain.shared.storage;

/**
 * O ambiente nao tem o storage de imagens configurado (as variaveis R2_* — passo a passo no vupth-docs).
 * Mapeada para HTTP 503 com code STORAGE_NOT_CONFIGURED: e o servidor que falta, nao o pedido.
 */
public class StorageNotConfiguredException extends RuntimeException {
    public StorageNotConfiguredException() {
        super("Upload de imagem indisponivel neste ambiente: o storage (Cloudflare R2) nao esta configurado.");
    }
}
