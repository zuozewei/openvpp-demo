package com.openvpp.iot.protocol;

/**
 * 协议落地三态 —— 专栏第 48 篇「菜单与后厨之间的三档落差」。
 *
 * 判定标准很硬：有编解码、点位表、命令处理器或网关 Runner 四者之一
 * 的落地代码才算实现；「枚举有值」不算。
 */
public enum ProtocolStatus {
    /** 在用：有实现且真实设备在协议后面跑 */
    IN_USE,
    /** 预制：有实现但业务未接线（公司预制件，按需出库） */
    PREPARED,
    /** 声明：仅枚举值，无任何落地代码（应标菜单项） */
    DECLARED
}
