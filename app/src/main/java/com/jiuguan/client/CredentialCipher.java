package com.jiuguan.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Versioned AES-GCM envelope; contains no Android APIs so authentication is host-testable. */
public final class CredentialCipher {
    private static final int VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_PLAINTEXT_BYTES = 65536;

    private CredentialCipher() { }

    public static String encrypt(SecretKey key, String scope, byte[] plaintext)
            throws GeneralSecurityException {
        if (scope == null || plaintext == null || plaintext.length > MAX_PLAINTEXT_BYTES)
            throw new GeneralSecurityException("凭据内容无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // AndroidKeyStore requires the provider to generate a new, unpredictable IV.
        cipher.init(Cipher.ENCRYPT_MODE, key);
        cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(plaintext);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != IV_BYTES) throw new GeneralSecurityException("加密随机数无效");
        byte[] envelope = new byte[2 + iv.length + encrypted.length];
        envelope[0] = VERSION;
        envelope[1] = (byte) iv.length;
        System.arraycopy(iv, 0, envelope, 2, iv.length);
        System.arraycopy(encrypted, 0, envelope, 2 + iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(envelope);
    }

    public static byte[] decrypt(SecretKey key, String scope, String encoded)
            throws GeneralSecurityException {
        if (scope == null || encoded == null || encoded.length() > 90000)
            throw new GeneralSecurityException("已保存凭据无效");
        final byte[] envelope;
        try { envelope = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new GeneralSecurityException("已保存凭据无效", e); }
        if (envelope.length < 2 + IV_BYTES + TAG_BITS / 8 || envelope[0] != VERSION
                || envelope[1] != IV_BYTES || envelope.length > 2 + IV_BYTES + TAG_BITS / 8 + MAX_PLAINTEXT_BYTES)
            throw new GeneralSecurityException("已保存凭据版本或长度无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
                new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(envelope, 2, 2 + IV_BYTES)));
        cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        return cipher.doFinal(envelope, 2 + IV_BYTES, envelope.length - 2 - IV_BYTES);
    }
}
