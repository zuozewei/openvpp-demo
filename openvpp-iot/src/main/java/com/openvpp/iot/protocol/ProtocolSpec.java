package com.openvpp.iot.protocol;

/**
 * 协议条目 —— 注册表的一行：名字 + 分类 + 落地状态 + 备注。
 *
 * 备注字段是三态判定的证据位：「在用」要写设备形态，
 * 「预制」要写实现形态与未接线原因，「声明」要写应标来源——
 * 没有证据的条目不允许标注 IN_USE。
 */
public final class ProtocolSpec {

    private final String name;
    private final ProtocolCategory category;
    private final ProtocolStatus status;
    private final String note;

    public ProtocolSpec(String name, ProtocolCategory category, ProtocolStatus status,
                        String note) {
        this.name = name;
        this.category = category;
        this.status = status;
        this.note = note == null ? "" : note;
    }

    public String name() {
        return name;
    }

    public ProtocolCategory category() {
        return category;
    }

    public ProtocolStatus status() {
        return status;
    }

    public String note() {
        return note;
    }
}
