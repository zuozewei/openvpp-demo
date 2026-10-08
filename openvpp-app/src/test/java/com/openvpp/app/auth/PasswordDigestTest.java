package com.openvpp.app.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 口令散列单测：格式、匹配、盐隔离与异常输入。
 */
class PasswordDigestTest {

    @Test
    void hashUsesSaltDollarHexFormat() {
        String hashed = PasswordDigest.hash("Openvpp@2026", "salt-demo");
        int separatorIndex = hashed.indexOf('$');
        assertTrue(separatorIndex > 0, "散列值应为 salt$hex 结构");
        assertEquals("salt-demo", hashed.substring(0, separatorIndex));
        // 64 位十六进制 = 32 字节 SHA-256 摘要
        assertEquals(64, hashed.substring(separatorIndex + 1).length());
    }

    @Test
    void matchesAcceptsCorrectPassword() {
        String hashed = PasswordDigest.hash("Openvpp@2026", "salt-demo");
        assertTrue(PasswordDigest.matches("Openvpp@2026", hashed));
    }

    @Test
    void matchesRejectsWrongPassword() {
        String hashed = PasswordDigest.hash("Openvpp@2026", "salt-demo");
        assertFalse(PasswordDigest.matches("openvpp@2026", hashed));
        assertFalse(PasswordDigest.matches("Openvpp@2027", hashed));
        assertFalse(PasswordDigest.matches("", hashed));
    }

    @Test
    void differentSaltsProduceDifferentHashes() {
        String hashA = PasswordDigest.hash("Openvpp@2026", "salt-a");
        String hashB = PasswordDigest.hash("Openvpp@2026", "salt-b");
        assertNotEquals(hashA, hashB);
    }

    @Test
    void matchesHandlesMalformedStoredHash() {
        assertFalse(PasswordDigest.matches("Openvpp@2026", "no-separator"));
        assertFalse(PasswordDigest.matches("Openvpp@2026", "$"));
        assertFalse(PasswordDigest.matches("Openvpp@2026", null));
        assertFalse(PasswordDigest.matches(null, "salt$abcd"));
    }
}
