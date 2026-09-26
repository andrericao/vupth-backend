package com.ospaulos.vupth.domain.shared.storage;

/**
 * Onde as imagens moram (foto de perfil — Andre 25/09: Cloudflare R2). O dominio so conhece esta porta;
 * o R2 fica na infraestrutura, e trocar de fornecedor (o R2 fala a API do S3) e configuracao.
 */
public interface ImageStorage {

    /** Grava a imagem na chave e devolve a URL publica dela. */
    String store(String key, byte[] content, String contentType);

    /** Apaga a imagem desta URL, se ela for deste storage; URL de fora e ignorada. */
    void deleteByUrl(String publicUrl);
}
