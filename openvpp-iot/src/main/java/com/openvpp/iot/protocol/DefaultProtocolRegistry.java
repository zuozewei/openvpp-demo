package com.openvpp.iot.protocol;

/**
 * 默认协议注册表 —— 专栏第 48 篇协议地图的教学快照（三态如实标注）。
 *
 * 这是那张「诚实的地图」：46 项菜单里真正在跑的约 20 项，
 * 预制待命的若干，纯声明的十余项——每条备注都写证据。
 */
public final class DefaultProtocolRegistry {

    private DefaultProtocolRegistry() {
    }

    /** 专栏第 48 篇第一节地图的教学快照（节选，非全量 46 项）。 */
    public static ProtocolRegistry create() {
        ProtocolRegistry r = new ProtocolRegistry();
        // 通用 IoT 传输：主力
        r.register(new ProtocolSpec("MQTT", ProtocolCategory.GENERAL_IOT, ProtocolStatus.IN_USE,
                "主力通道：商用 broker 扛连接 + 网关型 broker 做数据面路由，双轨并存"));
        r.register(new ProtocolSpec("CoAP", ProtocolCategory.GENERAL_IOT, ProtocolStatus.PREPARED,
                "Californium 网关在库（通道 URL→资源树），本交付无低功耗设备，业务未接线"));
        r.register(new ProtocolSpec("LwM2M", ProtocolCategory.GENERAL_IOT, ProtocolStatus.PREPARED,
                "Leshan 网关在库（Observe 订阅 + 写资源下行），仅加载三个基础对象模型"));
        r.register(new ProtocolSpec("HTTP", ProtocolCategory.GENERAL_IOT, ProtocolStatus.IN_USE,
                "HTTP(S) 网关在用（短连接型 JSON 上报设备）"));
        r.register(new ProtocolSpec("TCP", ProtocolCategory.GENERAL_IOT, ProtocolStatus.IN_USE,
                "Netty TCP/UDP 组件层，HEX 透传设备承载"));
        r.register(new ProtocolSpec("DDS", ProtocolCategory.GENERAL_IOT, ProtocolStatus.DECLARED,
                "仅枚举声明（应标菜单项），无任何落地代码"));
        r.register(new ProtocolSpec("XMPP", ProtocolCategory.GENERAL_IOT, ProtocolStatus.DECLARED,
                "仅枚举声明，无实现"));
        // 电力规约
        r.register(new ProtocolSpec("DLT645-1997", ProtocolCategory.POWER, ProtocolStatus.PREPARED,
                "点位表在用（含厂商私有扩展），帧编解码为预制件未接线"));
        r.register(new ProtocolSpec("DLT645-2007", ProtocolCategory.POWER, ProtocolStatus.PREPARED,
                "点位表在用（4 字节数据标识），电表实走厂商网关转 MQTT"));
        r.register(new ProtocolSpec("DLT698.45", ProtocolCategory.POWER, ProtocolStatus.DECLARED,
                "仅枚举声明，无 codec/无点位表"));
        // 工控/楼宇
        r.register(new ProtocolSpec("ModbusRTU", ProtocolCategory.INDUSTRIAL, ProtocolStatus.IN_USE,
                "下行调控主力：云端预构建 HEX 串（0x06/0x10），带非标 CRC 反转兼容开关"));
        r.register(new ProtocolSpec("ModbusTCP", ProtocolCategory.INDUSTRIAL, ProtocolStatus.IN_USE,
                "同 RTU 的命令族，无 CRC、走 TCP 通道"));
        r.register(new ProtocolSpec("BACnetIP", ProtocolCategory.INDUSTRIAL, ProtocolStatus.IN_USE,
                "参数类 + 命令映射在用（楼宇自控交付）"));
        r.register(new ProtocolSpec("OPCUA", ProtocolCategory.INDUSTRIAL, ProtocolStatus.PREPARED,
                "参数类在库，未见独立网关 Runner"));
        // 视频
        r.register(new ProtocolSpec("GB28181", ProtocolCategory.VIDEO, ProtocolStatus.DECLARED,
                "仅枚举声明，无 codec、无网关"));
        r.register(new ProtocolSpec("ONVIF", ProtocolCategory.VIDEO, ProtocolStatus.DECLARED,
                "仅枚举声明"));
        // 交通/环保/水文
        r.register(new ProtocolSpec("HJ212", ProtocolCategory.TRANSPORT_ENV, ProtocolStatus.DECLARED,
                "仅枚举声明（环保污染源在线监控）"));
        r.register(new ProtocolSpec("SL651", ProtocolCategory.TRANSPORT_ENV, ProtocolStatus.DECLARED,
                "仅枚举声明（水文遥测）"));
        // 定制直连
        r.register(new ProtocolSpec("CHARGING_PILE_VENDOR_A", ProtocolCategory.CUSTOM_DIRECT,
                ProtocolStatus.IN_USE, "充电桩厂商直连，命令映射走通用通道"));
        r.register(new ProtocolSpec("TEMP_PANEL_4G", ProtocolCategory.CUSTOM_DIRECT,
                ProtocolStatus.IN_USE, "4G 温控面板，认证按型号定制（HmacSHA1+盐）"));
        return r;
    }
}
