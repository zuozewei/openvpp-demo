package com.openvpp.app.auth;

import com.openvpp.common.context.RoleType;

import java.util.List;
import java.util.Map;

/**
 * 角色目录：RoleType 与前端会话/界面契约的映射中枢。
 *
 * 1. code：前端会话与路由守卫使用的角色短码（platform/operator/station），
 *    与 openvpp-admin 脚手架 mock/accounts.js 的 role 取值严格一致；
 * 2. roleName：界面展示名，与脚手架 roleName 一致；
 * 3. allowedOperations：当前角色允许操作清单（3.5.3 接口契约第 1 行"允许操作"），
 *    操作码与后续篇业务接口一一兑现；未兑现的页面入口只允许展示、不允许声称已完成。
 */
public final class RoleCatalog {

    private static final Map<RoleType, String> CODES = Map.of(
            RoleType.PLATFORM_ADMIN, "platform",
            RoleType.OPERATOR, "operator",
            RoleType.STATION_OPERATOR, "station");

    private static final Map<RoleType, String> ROLE_NAMES = Map.of(
            RoleType.PLATFORM_ADMIN, "平台运营",
            RoleType.OPERATOR, "运营商",
            RoleType.STATION_OPERATOR, "场站运营");

    private static final Map<RoleType, List<String>> ALLOWED_OPERATIONS = Map.of(
            RoleType.PLATFORM_ADMIN, List.of(
                    "event:create", "event:publish", "event:close",
                    "allocation:assign",
                    "assessment:review",
                    "settlement:manage", "settlement:correct",
                    "audit:view"),
            RoleType.OPERATOR, List.of(
                    "declaration:confirm",
                    "dispatch:manage",
                    "execution:monitor",
                    "assessment:submit",
                    "settlement:view"),
            RoleType.STATION_OPERATOR, List.of(
                    "declaration:submit",
                    "resource:maintain",
                    "execution:feedback",
                    "settlement:view-own"));

    private RoleCatalog() {
    }

    /** 前端会话角色短码（platform/operator/station） */
    public static String codeOf(RoleType role) {
        return CODES.get(role);
    }

    /** 角色展示名（与前端脚手架一致） */
    public static String roleNameOf(RoleType role) {
        return ROLE_NAMES.get(role);
    }

    /** 当前角色允许操作清单（操作码口径，随后续篇业务接口逐项兑现） */
    public static List<String> allowedOperationsOf(RoleType role) {
        return ALLOWED_OPERATIONS.get(role);
    }
}
