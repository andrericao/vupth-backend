package com.ospaulos.vupth.domain.feature;

/** Chave que nao existe. Mapeada para HTTP 404 com code APP_FEATURE_NOT_FOUND. */
public class AppFeatureNotFoundException extends RuntimeException {
    public AppFeatureNotFoundException(String key) {
        super("Chave nao encontrada: " + key);
    }
}
