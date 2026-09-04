package com.logmonitor.service;

import com.logmonitor.config.LlmProperties;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class LlmSecretService {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final LlmProperties properties;
    private final SecureRandom random = new SecureRandom();
    private SecretKey masterKey;

    public LlmSecretService(LlmProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        try {
            byte[] decoded = Base64.getDecoder().decode(properties.getMasterKey() == null ? "" : properties.getMasterKey());
            if (decoded.length != 32) throw new IllegalArgumentException();
            masterKey = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("LLM_CONFIG_MASTER_KEY 必须是 Base64 编码的 32 字节密钥");
        }
    }

    public EncryptedSecret encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("LLM API Key 加密失败", exception);
        }
    }

    public String decrypt(String ciphertext, String nonce) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey,
                    new GCMParameterSpec(TAG_BITS, Base64.getDecoder().decode(nonce)));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("LLM API Key 无法解密，请检查主密钥", exception);
        }
    }

    public record EncryptedSecret(String ciphertext, String nonce) {}
}
