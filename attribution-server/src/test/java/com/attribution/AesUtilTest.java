package com.attribution;

import com.attribution.common.util.AesUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AesUtilTest {

    private static final String KEY = "test-32-char-exact-key-data!!";

    @Test
    void encryptAndDecrypt_roundTrip() {
        String original = "my-secret-api-key-for-whale";
        String encrypted = AesUtil.encrypt(original, KEY);
        assertNotNull(encrypted);
        assertNotEquals(original, encrypted);

        String decrypted = AesUtil.decrypt(encrypted, KEY);
        assertEquals(original, decrypted);
    }

    @Test
    void encrypt_producesDifferentOutputEachTime() {
        String plain = "same-text";
        String e1 = AesUtil.encrypt(plain, KEY);
        String e2 = AesUtil.encrypt(plain, KEY);
        assertNotEquals(e1, e2, "AES-GCM with random IV should produce different ciphertext each time");
    }

    @Test
    void decrypt_withWrongKey_throwsException() {
        String encrypted = AesUtil.encrypt("secret", KEY);
        assertThrows(RuntimeException.class, () ->
                AesUtil.decrypt(encrypted, "wrong-32-char-key-for-test!!!"));
    }

    @Test
    void encrypt_handlesEmptyString() {
        String encrypted = AesUtil.encrypt("", KEY);
        assertNotNull(encrypted);
        assertEquals("", AesUtil.decrypt(encrypted, KEY));
    }

    @Test
    void encrypt_handlesChineseCharacters() {
        String original = "华为鲸鸿动能密钥";
        String encrypted = AesUtil.encrypt(original, KEY);
        assertEquals(original, AesUtil.decrypt(encrypted, KEY));
    }

    @Test
    void encrypt_handlesBase64Input() {
        // WhaleHongDong keys are Base64 strings
        String base64Key = "YWJjZGVmZ2hpamtsbW5vcHFyc3Q=";
        String encrypted = AesUtil.encrypt(base64Key, KEY);
        assertEquals(base64Key, AesUtil.decrypt(encrypted, KEY));
    }
}
