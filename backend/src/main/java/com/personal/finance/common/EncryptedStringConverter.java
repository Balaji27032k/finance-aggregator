package com.personal.finance.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private final PayloadCipher cipher;

    public EncryptedStringConverter(PayloadCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : cipher.decrypt(dbData);
    }
}
