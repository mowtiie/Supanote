package com.mowtiie.supanote.data.local;

import java.security.GeneralSecurityException;

/**
 * Encrypts the text of cached notes. {@code context} is bound to the ciphertext (as authenticated data), so a
 * value cannot be moved to another row and still decrypt.
 */
public interface NoteCrypto {

    String encrypt(String plain, String context) throws GeneralSecurityException;

    /** @return the plain text, or null if the value cannot be decrypted (for example the key was lost). */
    String decrypt(String stored, String context);
}
