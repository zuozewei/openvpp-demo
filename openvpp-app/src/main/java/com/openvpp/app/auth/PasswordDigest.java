package com.openvpp.app.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 教学口径口令散列：固定盐 + 迭代 SHA-256，输出 salt$hex 单字段存储。
 *
 * 口径说明（教学边界）：本类是专栏演示用的教学实现——加盐与迭代让彩虹表/裸字典
 * 攻击在演示层面不可行，但算法强度仍低于 BCrypt/argon2 等自适应代价算法；
 * 容器化交付形态的口令散列与会话存储属后续验收篇，本篇不展开。
 * 比对使用常量时间比较（MessageDigest.isEqual），避免响应时差泄漏前缀匹配位数。
 */
public final class PasswordDigest {

    private static final String SHA_ALGO = "SHA-256";
    private static final int ITERATIONS = 1024;
    private static final char SEPARATOR = '$';

    private PasswordDigest() {
    }

    /** 生成 salt$hex 形态的散列值（salt 由调用方持有，演示账号使用固定盐便于复算讲解） */
    public static String hash(String password, String salt) {
        if (password == null || salt == null) {
            throw new IllegalArgumentException("口令与盐均不能为空");
        }
        return salt + SEPARATOR + hexDigest(password, salt);
    }

    /**
     * 校验口令与库中散列是否匹配；storedHash 无 salt$hex 结构时按不匹配处理，不抛异常。
     */
    public static boolean matches(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }
        int separatorIndex = storedHash.indexOf(SEPARATOR);
        if (separatorIndex <= 0) {
            return false;
        }
        String salt = storedHash.substring(0, separatorIndex);
        String expectedHex = storedHash.substring(separatorIndex + 1);
        return constantTimeEquals(hexDigest(password, salt), expectedHex);
    }

    /** 迭代散列的十六进制摘要（不含盐前缀，存储与比对共用同一路径） */
    private static String hexDigest(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_ALGO);
            byte[] digest = md.digest((salt + SEPARATOR + password).getBytes(StandardCharsets.UTF_8));
            for (int i = 1; i < ITERATIONS; i++) {
                digest = md.digest(digest);
            }
            return toHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /** 常量时间比对：不用 String.equals——后者逐字符短路返回，时序攻击可泄漏前缀匹配长度 */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
