package com.mowtiie.supanote.data.local;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** {@link NoteCrypto} with an AES-256-GCM key that lives in the Android Keystore and never leaves it. */
public class KeystoreNoteCrypto implements NoteCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final String keyAlias;

    public KeystoreNoteCrypto(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    @Override
    public String encrypt(String plain, String context) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, SecureStorage.getOrCreateKey(keyAlias));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        if (iv.length != IV_LENGTH_BYTES) throw new GeneralSecurityException("Unexpected IV length");
        byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

        byte[] blob = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(cipherText, 0, blob, iv.length, cipherText.length);
        return Base64.encodeToString(blob, Base64.NO_WRAP);
    }

    @Override
    public String decrypt(String stored, String context) {
        if (stored == null) return null;
        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            if (blob.length <= IV_LENGTH_BYTES) return null;

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, SecureStorage.getOrCreateKey(keyAlias),
                    new GCMParameterSpec(TAG_LENGTH_BITS, Arrays.copyOfRange(blob, 0, IV_LENGTH_BYTES)));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(blob, IV_LENGTH_BYTES, blob.length - IV_LENGTH_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
