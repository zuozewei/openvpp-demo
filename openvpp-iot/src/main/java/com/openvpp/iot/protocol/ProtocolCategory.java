package com.openvpp.iot.protocol;

/**
 * 协议领域分类 —— 专栏第 48 篇协议地图的六大类。
 */
public enum ProtocolCategory {
    /** 电力规约（DL/T645、DL/T698.45 等） */
    POWER,
    /** 工控/楼宇自控（Modbus、BACnet、OPC UA、VRV 等） */
    INDUSTRIAL,
    /** 视频（GB/T 28181、ONVIF） */
    VIDEO,
    /** 交通/环保/水文/消防（JT/T 808、HJ 212、SL 651、GB/T 26875） */
    TRANSPORT_ENV,
    /** 通用 IoT 传输（MQTT、CoAP、LwM2M、HTTP、TCP/UDP 等） */
    GENERAL_IOT,
    /** 定制直连设备（充电桩厂商、温控面板、HEX 透传等） */
    CUSTOM_DIRECT
}
