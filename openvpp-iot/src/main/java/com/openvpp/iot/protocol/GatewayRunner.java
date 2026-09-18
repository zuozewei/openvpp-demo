package com.openvpp.iot.protocol;

import java.util.Map;

/**
 * 协议网关契约 —— 专栏第 48 篇「四方法契约」。
 *
 * 每个协议网关只需实现四个方法：通道注册（平台「通道 URL」→ 协议端点）、
 * 通道注销、下行命令（平台命令 → 协议写操作）、参数校验。
 * 协议网关只负责收发字节与回调；字节怎么变成点位由各设备产品的
 * Processor 实现，再经事件母语上行。
 */
public interface GatewayRunner {

    /**
     * 设备通道注册：把平台配置的通道 URL 映射为协议端点
     * （CoAP 资源树、LwM2M Observe 路径都从这里来）。
     */
    void registerChannel(String deviceSn, Map<String, String> channels);

    /** 通道注销。 */
    void unRegisterChannel(String deviceSn);

    /**
     * 平台下行命令 → 协议写操作。
     *
     * @return true=已受理；false=该网关不支持下行（只收不发，如教学版 CoAP 上行模型）
     */
    boolean onCommand(String deviceSn, String channelUrl, String command);

    /** 校验网络/协议参数组合是否合法（部署校验器）。 */
    void verifyParameters(Map<String, Object> networkSetting, Map<String, Object> protoSetting);
}
