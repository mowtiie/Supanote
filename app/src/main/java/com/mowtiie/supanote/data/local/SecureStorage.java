package com.mowtiie.supanote.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small string key/value store whose values are encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves it. Each value is bound to its name (as GCM associated data), so a
 * ciphertext cannot be swapped between entries. Values that cannot be decrypted are treated as absent.
 */
public class SecureStorage {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    // What androidx.security:security-crypto used before it was removed; cleaned up on first open.
    private static final String LEGACY_SESSION_PREFS = "supabase_session_secure";
    private static final String[] LEGACY_KEYSET_PREFS = {
            "__androidx_security_crypto_encrypted_prefs_key_keyset__",
            "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    };
    private static final String LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_";

    private final SharedPreferences prefs;
    private final String keyAlias;

    private SecureStorage(SharedPreferences prefs, String keyAlias) {
        this.prefs = prefs;
        this.keyAlias = keyAlias;
    }

    /**
     * Opens (creating the Keystore key if needed) the store backed by the preferences file {@code prefsName}.
     * Slow on first use because of Keystore work, so call it off the main thread.
     */
    public static SecureStorage open(Context context, String prefsName, String keyAlias) throws GeneralSecurityException {
        Context appContext = context.getApplicationContext();
        purgeLegacyStorage(appContext);
        getOrCreateKey(keyAlias);
        return new SecureStorage(appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE), keyAlias);
    }

    /** Deletes the Keystore key and the stored values, so a broken store can be recreated from scratch. */
    public static void destroy(Context context, String prefsName, String keyAlias) {
        context.getApplicationContext().deleteSharedPreferences(prefsName);
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(keyAlias);
        } catch (Exception ignored) {
            // Nothing more can be done; the next open will generate a new key or fail again.
        }
    }

    private static void purgeLegacyStorage(Context context) {
        context.deleteSharedPreferences(LEGACY_SESSION_PREFS);
        for (String name : LEGACY_KEYSET_PREFS) {
            context.deleteSharedPreferences(name);
        }
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(LEGACY_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(LEGACY_MASTER_KEY_ALIAS);
            }
        } catch (Exception ignored) {
            // Best effort: a leftover unused key is harmless.
        }
    }

    static SecretKey getOrCreateKey(String alias) throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) {
                KeyStore.Entry entry = keyStore.getEntry(alias, null);
                if (entry instanceof KeyStore.SecretKeyEntry) {
                    return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                }
            }

            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(
                    alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /** @return the decrypted value, or null if it is absent or can no longer be decrypted. */
    public String get(String name) {
        String stored = prefs.getString(name, null);
        if (stored == null) return null;

        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            if (blob.length <= IV_LENGTH_BYTES) return null;

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(keyAlias),
                    new GCMParameterSpec(TAG_LENGTH_BITS, Arrays.copyOfRange(blob, 0, IV_LENGTH_BYTES)));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(blob, IV_LENGTH_BYTES, blob.length - IV_LENGTH_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Stores {@code values}; a null value removes that entry. Throws if encryption is not possible. */
    public void putAll(Map<String, String> values) throws GeneralSecurityException {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                editor.remove(entry.getKey());
            } else {
                editor.putString(entry.getKey(), encrypt(entry.getKey(), entry.getValue()));
            }
        }
        editor.apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private String encrypt(String name, String value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyAlias));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv.length != IV_LENGTH_BYTES) throw new GeneralSecurityException("Unexpected IV length");
        byte[] cipherText = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

        byte[] blob = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(cipherText, 0, blob, iv.length, cipherText.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }
}
