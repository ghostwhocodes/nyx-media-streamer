package com.nyx.config;

import at.favre.lib.crypto.bcrypt.BCrypt;

public final class AuthUtils {
    private AuthUtils() {}

    public static String hashPassword(String plaintext) {
        return BCrypt.withDefaults().hashToString(12, plaintext.toCharArray());
    }

    public static boolean verifyPassword(String plaintext, String hash) {
        return BCrypt.verifyer().verify(plaintext.toCharArray(), hash).verified;
    }
}
