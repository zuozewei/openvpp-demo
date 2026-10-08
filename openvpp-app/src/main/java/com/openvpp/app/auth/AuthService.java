package com.openvpp.app.auth;

import com.openvpp.app.persistence.AccountRepository;
import com.openvpp.app.persistence.AuditLogRepository;
import com.openvpp.common.context.UnauthorizedAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 教学登录服务：账号口令校验 + 会话开立 + 登录审计留痕。
 *
 * 安全边界：
 * 1. 口令只与库中散列比对（PasswordDigest 常量时间比较），成功与失败均留审计（结果分列）；
 * 2. 账号或口令错误统一抛 UnauthorizedAccessException（映射 401），不区分"账号不存在"
 *    与"口令错误"，避免给账号枚举留口；
 * 3. 登录响应的 token 是服务端会话的唯一凭证，身份与数据范围一律以会话为准。
 */
@Service
public class AuthService {

    public static final String ACTION_LOGIN = "LOGIN";
    public static final String TARGET_ACCOUNT = "ACCOUNT";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_REJECTED = "REJECTED";

    private final AccountRepository accountRepository;
    private final SessionStore sessionStore;
    private final AuditLogRepository auditLogRepository;

    public AuthService(AccountRepository accountRepository,
                       SessionStore sessionStore,
                       AuditLogRepository auditLogRepository) {
        this.accountRepository = accountRepository;
        this.sessionStore = sessionStore;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 教学登录：校验账号口令，成功则开立服务端会话并返回会话凭证。
     *
     * @throws IllegalArgumentException      账号或密码为空（映射 400）
     * @throws UnauthorizedAccessException 账号或密码错误（映射 401）
     */
    public LoginResult login(String account, String password) {
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("账号不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        Optional<Account> found = accountRepository.findByLogin(account);
        if (found.isEmpty() || !PasswordDigest.matches(password, found.get().getPasswordHash())) {
            // 失败同样留痕：能取到账号则按账号口径登记，取不到则账号名登记、租户/角色记 '-'
            Account actual = found.orElse(null);
            auditLogRepository.insert(account,
                    actual == null ? "-" : actual.getTenantId(),
                    actual == null ? "-" : actual.getRole().name(),
                    ACTION_LOGIN, TARGET_ACCOUNT, account, "-", RESULT_REJECTED, "账号或密码错误");
            throw new UnauthorizedAccessException("账号或密码错误");
        }
        Account actual = found.get();
        SessionEntry entry = sessionStore.create(actual);
        auditLogRepository.insert(actual.getLogin(), actual.getTenantId(), actual.getRole().name(),
                ACTION_LOGIN, TARGET_ACCOUNT, actual.getLogin(), "-", RESULT_SUCCESS, "登录成功");
        return new LoginResult(entry.getToken(), RoleCatalog.codeOf(actual.getRole()),
                RoleCatalog.roleNameOf(actual.getRole()), actual.getDisplayName());
    }
}
