package com.openvpp.settlement.charge;

/**
 * 分账主体 —— 多级分账的三方业务角色（第 57 篇底座，3.5.2 核算口径段：
 * "分账采用平台、运营商、场站的可配置比例"）。
 *
 * 三方标识由结算域按归属链解析，不允许请求方自填：
 * 平台方 = 事件组织方租户，运营商 = 场站归属运营主体，场站 = 评估场站
 * （归属链见 openvpp-resource 的租户/主体/场站三级档案）。
 */
public enum AllocationParty {

    /** 平台方：事件组织方租户（组织者账号所属租户） */
    PLATFORM("平台方"),

    /** 运营商：场站归属的运营主体（聚合运营方） */
    OPERATOR("运营商"),

    /** 场站：评估执行场站（资源所属最小单元） */
    STATION("场站");

    private final String label;

    AllocationParty(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
