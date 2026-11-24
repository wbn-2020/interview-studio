package com.wenjie.studio.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/** 数据库只保存 AES-GCM 密文；主密钥缺失或无效时整个 Web 编辑入口保持关闭。 */
@Component
public final class AiConfigCrypto {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte FORMAT_VERSION = 1;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AiConfigCrypto(@Value("${studio.ai.config-key:}") String encodedKey) {
        SecretKeySpec parsed = null;
        try {
            if (encodedKey != null && !encodedKey.isBlank()) {
                byte[] decoded = Base64.getDecoder().decode(encodedKey.strip());
                if (decoded.length == KEY_BYTES) parsed = new SecretKeySpec(decoded, "AES");
            }
        } catch (IllegalArgumentException ignored) {
            // 配置错误不输出原值；缺少有效密钥时仍允许使用原有环境变量配置。
        }
        key = parsed;
    }

    public boolean available() { return key != null; }

    public byte[] encrypt(String owner, long revision, String plaintext) {
        requireKey();
        if (plaintext == null || plaintext.isEmpty()) return new byte[0];
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(owner, revision));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length).put(FORMAT_VERSION).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AI 配置密钥加密失败");
        }
    }

    public String decrypt(String owner, long revision, byte[] payload) {
        requireKey();
        if (payload == null || payload.length == 0) return "";
        try {
            if (payload.length < 1 + IV_BYTES + TAG_BITS / 8 || payload[0] != FORMAT_VERSION) {
                throw new GeneralSecurityException("Unsupported ciphertext format");
            }
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            buffer.get();
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(owner, revision));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AI 配置密钥解密失败");
        }
    }

    private void requireKey() {
        if (!available()) throw new IllegalStateException("AI 配置主密钥未配置");
    }

    private static byte[] aad(String owner, long revision) {
        return ("studio-ai-config-v1\n" + owner + "\n" + revision).getBytes(StandardCharsets.UTF_8);
    }
}
