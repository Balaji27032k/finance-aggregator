package com.personal.finance.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PayloadCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private final PayloadCipher cipher = new PayloadCipher(KEY);

    @Test
    void roundTripRestoresThePlaintext() {
        String text = "Rs 500 debited from A/c XX0000 ₹";
        assertThat(cipher.decrypt(cipher.encrypt(text))).isEqualTo(text);
    }

    @Test
    void ciphertextHidesThePlaintextAndUsesAFreshIvEachTime() {
        String a = cipher.encrypt("debited by 413.00");
        String b = cipher.encrypt("debited by 413.00");
        assertThat(a).startsWith("v1:").doesNotContain("413").isNotEqualTo(b);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        String stored = cipher.encrypt("credited Rs 10");
        byte[] raw = Base64.getDecoder().decode(stored.substring(3));
        raw[raw.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String stored = cipher.encrypt("credited Rs 10");
        PayloadCipher other = new PayloadCipher(Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes()));
        assertThatThrownBy(() -> other.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keysThatAreNot32BytesAreRejected() {
        assertThatThrownBy(() -> new PayloadCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PayloadCipher("not base64 !!")).isInstanceOf(IllegalStateException.class);
    }
}
