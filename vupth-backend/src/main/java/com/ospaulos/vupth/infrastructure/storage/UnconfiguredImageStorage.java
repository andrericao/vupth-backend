package com.ospaulos.vupth.infrastructure.storage;

import com.ospaulos.vupth.domain.shared.storage.ImageStorage;
import com.ospaulos.vupth.domain.shared.storage.StorageNotConfiguredException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Ambiente sem R2 (dev local, testes, ou antes de alguem seguir o passo a passo): o upload responde
 * 503 STORAGE_NOT_CONFIGURED em vez de subir quebrado. Apagar e no-op — nao ha o que apagar.
 */
@Component
@ConditionalOnMissingBean(R2ImageStorage.class)
public class UnconfiguredImageStorage implements ImageStorage {

    @Override
    public String store(String key, byte[] content, String contentType) {
        throw new StorageNotConfiguredException();
    }

    @Override
    public void deleteByUrl(String publicUrl) {
        // nada gravado aqui
    }
}
