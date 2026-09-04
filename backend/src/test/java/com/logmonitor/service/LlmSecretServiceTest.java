package com.logmonitor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logmonitor.config.LlmProperties;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LlmSecretServiceTest {
    private static final String KEY_ONE = key((byte) 1);
    private static final String KEY_TWO = key((byte) 2);

    @Test
    void encryptsWithUniqueNoncesAndRejectsWrongMasterKey() {
        LlmSecretService service = service(KEY_ONE);
        LlmSecretService.EncryptedSecret first = service.encrypt("sk-test-secret");
        LlmSecretService.EncryptedSecret second = service.encrypt("sk-test-secret");

        assertThat(first.ciphertext()).doesNotContain("sk-test-secret").isNotEqualTo(second.ciphertext());
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(service.decrypt(first.ciphertext(), first.nonce())).isEqualTo("sk-test-secret");
        assertThatThrownBy(() -> service(KEY_TWO).decrypt(first.ciphertext(), first.nonce()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("无法解密");
    }

    @Test
    void requiresA256BitBase64MasterKey() {
        assertThatThrownBy(() -> service("not-a-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    private LlmSecretService service(String key) {
        LlmProperties properties = new LlmProperties();
        properties.setMasterKey(key);
        LlmSecretService service = new LlmSecretService(properties);
        service.initialize();
        return service;
    }

    private static String key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
