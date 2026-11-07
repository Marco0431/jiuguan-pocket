package com.jiuguan.client;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;
import static org.junit.Assert.*;

public class CredentialCipherTest {
    private static SecretKey key() throws GeneralSecurityException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        return generator.generateKey();
    }

    @Test public void unicodeCredentialsRoundTrip() throws Exception {
        SecretKey key = key();
        String scope = "http://192.168.1.2:8003\n酒馆登录";
        byte[] plain = "用户名\u0000密码🔐\n".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(plain, CredentialCipher.decrypt(key, scope, CredentialCipher.encrypt(key, scope, plain)));
    }

    @Test public void everyEncryptionUsesANewProviderIv() throws Exception {
        SecretKey key = key();
        byte[] plain = "same password".getBytes(StandardCharsets.UTF_8);
        String first = CredentialCipher.encrypt(key, "origin\nrealm", plain);
        String second = CredentialCipher.encrypt(key, "origin\nrealm", plain);
        assertNotEquals(first, second);
        byte[] a = Base64.getDecoder().decode(first);
        byte[] b = Base64.getDecoder().decode(second);
        assertFalse(java.util.Arrays.equals(java.util.Arrays.copyOfRange(a, 2, 14), java.util.Arrays.copyOfRange(b, 2, 14)));
    }

    @Test public void ciphertextCannotBeCopiedToAnotherOriginOrRealm() throws Exception {
        SecretKey key = key();
        String encrypted = CredentialCipher.encrypt(key, "https://one.example:443\nfirst", new byte[]{1, 2, 3});
        rejects(key, "https://two.example:443\nfirst", encrypted);
        rejects(key, "https://one.example:443\nsecond", encrypted);
        rejects(key, "http://one.example:80\nfirst", encrypted);
    }

    @Test public void changedCiphertextAndWrongKeyAreRejected() throws Exception {
        SecretKey key = key();
        String encrypted = CredentialCipher.encrypt(key, "scope", new byte[]{1, 2, 3});
        byte[] envelope = Base64.getDecoder().decode(encrypted);
        envelope[envelope.length - 1] ^= 1;
        rejects(key, "scope", Base64.getEncoder().encodeToString(envelope));
        rejects(key(), "scope", encrypted);
    }

    @Test public void malformedAndUnknownVersionEnvelopesAreRejected() throws Exception {
        SecretKey key = key();
        rejects(key, "scope", "not base64!");
        rejects(key, "scope", "");
        rejects(key, "scope", null);
        byte[] envelope = Base64.getDecoder().decode(CredentialCipher.encrypt(key, "scope", new byte[0]));
        envelope[0] = 2;
        rejects(key, "scope", Base64.getEncoder().encodeToString(envelope));
        envelope[0] = 1; envelope[1] = 16;
        rejects(key, "scope", Base64.getEncoder().encodeToString(envelope));
    }

    private static void rejects(SecretKey key, String scope, String encrypted) throws Exception {
        try { CredentialCipher.decrypt(key, scope, encrypted); fail("Unauthenticated credential accepted"); }
        catch (GeneralSecurityException expected) { /* Fail closed. */ }
    }
}
