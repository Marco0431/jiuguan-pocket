package com.jiuguan.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** Passwords and usernames are persisted only in AndroidKeyStore-authenticated ciphertext. */
public final class CredentialStore {
    private static final String KEY_ALIAS = "com.jiuguan.client.credentials.v1";
    private static final String PREFIX = "c.";
    private static final Object LOCK = new Object();
    private static final Set<String> REVOKED = new HashSet<>();
    private static boolean storagePoisoned;
    private final SharedPreferences preferences;

    public static final class Entry {
        public final String username;
        public final String password;
        public final boolean autoLogin;

        private Entry(String username, String password, boolean autoLogin) {
            this.username = username;
            this.password = password;
            this.autoLogin = autoLogin;
        }
    }

    public CredentialStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("encrypted_credentials_v1", Context.MODE_PRIVATE);
    }

    public Entry get(String scope) {
        synchronized (LOCK) {
            if (scope == null || storagePoisoned || REVOKED.contains(scope)) return null;
            try {
                String encrypted = preferences.getString(entryKey(scope), null);
                if (encrypted == null) return null;
                return decode(CredentialCipher.decrypt(secretKey(false), scope, encrypted));
            } catch (GeneralSecurityException | RuntimeException e) {
                discard(scope);
                return null;
            }
        }
    }

    public void save(String scope, String username, String password, boolean autoLogin)
            throws GeneralSecurityException {
        synchronized (LOCK) {
            if (scope == null || scope.indexOf('\n') <= 0 || scope.length() > 4096
                    || username == null || password == null)
                throw new GeneralSecurityException("凭据内容无效");
            if (storagePoisoned) {
                wipeStorage();
                if (storagePoisoned) throw new GeneralSecurityException("无法安全保存凭据，请稍后重试");
            }
            String ciphertext = CredentialCipher.encrypt(secretKey(true), scope, encode(username, password, autoLogin));
            boolean committed;
            try { committed = preferences.edit().putString(entryKey(scope), ciphertext).commit(); }
            catch (RuntimeException e) { discard(scope); throw new GeneralSecurityException("无法保存凭据", e); }
            if (!committed) {
                discard(scope);
                throw new GeneralSecurityException("无法保存凭据，请检查设备存储空间");
            }
            REVOKED.remove(scope);
        }
    }

    public boolean hasAutoLogin(String serverAddress) {
        synchronized (LOCK) {
            if (storagePoisoned) return false;
            try {
                for (String scope : scopesForServer(serverAddress)) {
                    Entry entry = get(scope);
                    if (entry != null && entry.autoLogin) return true;
                }
            } catch (RuntimeException e) { return false; }
            return false;
        }
    }

    public void setAutoLoginForServer(String serverAddress, boolean enabled)
            throws GeneralSecurityException {
        synchronized (LOCK) {
            if (storagePoisoned) throw new GeneralSecurityException("凭据存储不可用，请重新保存账号密码");
            final List<String> scopes;
            try { scopes = scopesForServer(serverAddress); }
            catch (RuntimeException e) { throw new GeneralSecurityException("酒馆地址无效", e); }
            SharedPreferences.Editor editor = preferences.edit();
            List<String> changed = new ArrayList<>();
            for (String scope : scopes) {
                Entry entry = get(scope);
                if (entry == null) continue;
                String ciphertext = CredentialCipher.encrypt(secretKey(true), scope,
                        encode(entry.username, entry.password, enabled));
                editor.putString(entryKey(scope), ciphertext);
                changed.add(scope);
            }
            if (changed.isEmpty()) return;
            boolean committed;
            try { committed = editor.commit(); }
            catch (RuntimeException e) { for (String scope : changed) discard(scope); throw new GeneralSecurityException("无法更新自动登录设置", e); }
            if (!committed) {
                for (String scope : changed) discard(scope);
                throw new GeneralSecurityException("无法更新自动登录设置");
            }
        }
    }

    public void clearServer(String serverAddress) {
        synchronized (LOCK) {
            try {
                List<String> scopes = scopesForServer(serverAddress);
                SharedPreferences.Editor editor = preferences.edit();
                for (String scope : scopes) { REVOKED.add(scope); editor.remove(entryKey(scope)); }
                if (!editor.commit()) wipeStorage();
            } catch (RuntimeException e) { wipeStorage(); }
        }
    }

    public void clearAll() {
        synchronized (LOCK) { wipeStorage(); }
    }

    private List<String> scopesForServer(String address) {
        String originPrefix = AuthScope.origin(address) + "\n";
        List<String> scopes = new ArrayList<>();
        for (String key : preferences.getAll().keySet()) {
            if (!key.startsWith(PREFIX)) continue;
            try {
                String scope = new String(Base64.getUrlDecoder().decode(key.substring(PREFIX.length())), StandardCharsets.UTF_8);
                if (scope.startsWith(originPrefix) && entryKey(scope).equals(key)) scopes.add(scope);
            } catch (IllegalArgumentException ignored) { /* Not a valid credential key. */ }
        }
        return scopes;
    }

    private static String entryKey(String scope) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(scope.getBytes(StandardCharsets.UTF_8));
    }

    private static SecretKey secretKey(boolean create) throws GeneralSecurityException {
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) {
                java.security.Key key = store.getKey(KEY_ALIAS, null);
                if (key instanceof SecretKey) return (SecretKey) key;
                throw new GeneralSecurityException("设备密钥不可用");
            }
            if (!create) throw new GeneralSecurityException("设备密钥已丢失");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build());
            return generator.generateKey();
        } catch (IOException | RuntimeException e) {
            throw new GeneralSecurityException("设备安全存储不可用", e);
        }
    }

    private void discard(String scope) {
        REVOKED.add(scope); // Even a failed disk write must never return this entry again in this process.
        try { if (!preferences.edit().remove(entryKey(scope)).commit()) wipeStorage(); }
        catch (RuntimeException e) { wipeStorage(); }
    }

    private void wipeStorage() {
        storagePoisoned = true;
        boolean removedPreferences = false;
        boolean removedKey = false;
        try { removedPreferences = preferences.edit().clear().commit(); }
        catch (RuntimeException ignored) { }
        // Invalidates residual ciphertext across restarts if clearing the preferences failed.
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
            removedKey = true;
        } catch (GeneralSecurityException | IOException | RuntimeException ignored) { }
        if (removedPreferences && removedKey) { storagePoisoned = false; REVOKED.clear(); }
    }

    private static byte[] encode(String username, String password, boolean autoLogin)
            throws GeneralSecurityException {
        try {
            byte[] user = username.getBytes(StandardCharsets.UTF_8);
            byte[] pass = password.getBytes(StandardCharsets.UTF_8);
            if (user.length > 16384 || pass.length > 16384) throw new GeneralSecurityException("账号或密码过长");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(output);
            data.writeBoolean(autoLogin);
            data.writeInt(user.length); data.write(user);
            data.writeInt(pass.length); data.write(pass);
            data.flush();
            return output.toByteArray();
        } catch (IOException e) { throw new GeneralSecurityException("无法编码凭据", e); }
    }

    private static Entry decode(byte[] plaintext) throws GeneralSecurityException {
        try {
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(plaintext));
            boolean autoLogin = data.readBoolean();
            String user = readString(data);
            String pass = readString(data);
            if (data.available() != 0) throw new IOException("Trailing credential data");
            return new Entry(user, pass, autoLogin);
        } catch (IOException e) { throw new GeneralSecurityException("已保存凭据损坏", e); }
    }

    private static String readString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0 || length > 16384 || length > data.available()) throw new IOException("Invalid credential length");
        byte[] bytes = new byte[length];
        data.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
