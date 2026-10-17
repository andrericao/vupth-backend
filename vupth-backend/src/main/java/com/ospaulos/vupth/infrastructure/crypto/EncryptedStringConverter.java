package com.ospaulos.vupth.infrastructure.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/** Grava o campo cifrado e o le decifrado (S4). Gerido pelo Spring, para receber a chave do {@link DataCipher}. */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final DataCipher cipher;

    public EncryptedStringConverter(DataCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return cipher.decrypt(dbData);
    }
}
