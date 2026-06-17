package com.nyx.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthUtilsTest {
    @Test
    void hashPasswordProducesVerifiableHash() {
        String hash = AuthUtils.hashPassword("mypassword");
        assertThat(AuthUtils.verifyPassword("mypassword", hash)).isTrue();
    }

    @Test
    void verifyPasswordRejectsWrongPassword() {
        String hash = AuthUtils.hashPassword("correct");
        assertThat(AuthUtils.verifyPassword("wrong", hash)).isFalse();
    }

    @Test
    void verifyPasswordRejectsEmptyPassword() {
        String hash = AuthUtils.hashPassword("secret");
        assertThat(AuthUtils.verifyPassword("", hash)).isFalse();
    }

    @Test
    void hashPasswordProducesDifferentHashesForSameInput() {
        String first = AuthUtils.hashPassword("same");
        String second = AuthUtils.hashPassword("same");

        assertThat(first).isNotEqualTo(second);
        assertThat(AuthUtils.verifyPassword("same", first)).isTrue();
        assertThat(AuthUtils.verifyPassword("same", second)).isTrue();
    }
}
