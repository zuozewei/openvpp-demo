package com.openvpp.dispatch.decompose;

/**
 * 目标分解算法类型 —— 专栏第 46 篇四种分配算法。
 *
 * 每种算法「看」的资源特征不同：
 * <ul>
 *   <li>PROPORTIONAL 比例均衡：按档案可调电量均摊，无历史数据时的默认档</li>
 *   <li>COOPERATION 协同优先：按配合度 C = 1/(1+偏差均值) 挑「听话的」</li>
 *   <li>BENEFIT 收益优化：按分时电价下的收益代理，贵且负荷高的时段多担</li>
 *   <li>CONTROLLABILITY 可控优先：按反馈可达率 Rel，指令成功率优先</li>
 * </ul>
 * fromCode 对未知值兜底 PROPORTIONAL——分解入口是运营点按钮就跑的功能，
 * 前端误传参数（如画像偏好）不允许白屏。
 */
public enum DecomposeAlgoType {

    PROPORTIONAL("1"),
    COOPERATION("2"),
    BENEFIT("3"),
    CONTROLLABILITY("4");

    private final String code;

    DecomposeAlgoType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 前端算法编码 → 枚举；未知编码（含误传画像偏好）兜底比例均衡。 */
    public static DecomposeAlgoType fromCode(String code) {
        for (DecomposeAlgoType t : values()) {
            if (t.code.equals(code)) {
                return t;
            }
        }
        return PROPORTIONAL;
    }
}
