package com.openvpp.iot.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CoAP 资源树网关（教学示例）—— 专栏第 48 篇第四节。
 *
 * 亮点是 registerChannel：把平台配置的通道 URL（如 coapendpoints/home/room1/sensor1）
 * 递归映射成 CoAP 资源树——「平台通道模型 → CoAP URL 空间」的动态翻译。
 * 上行是「短连接型 JSON 上报」模型（observe 能力未用），
 * 下行 onCommand 返回 false（只收不发）。
 */
public class CoapResourceTreeGateway implements GatewayRunner {

    private final ProtocolEventBus eventBus;
    /** 资源树：URL 路径 → 通道归属设备（模拟 Californium 资源树） */
    private final Map<String, String> resourceTree = new LinkedHashMap<>();

    public CoapResourceTreeGateway(ProtocolEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public void registerChannel(String deviceSn, Map<String, String> channels) {
        for (String url : channels.keySet()) {
            String path = normalize(url);
            resourceTree.put(path, deviceSn);
        }
    }

    @Override
    public void unRegisterChannel(String deviceSn) {
        resourceTree.values().removeIf(owner -> owner.equals(deviceSn));
    }

    @Override
    public boolean onCommand(String deviceSn, String channelUrl, String command) {
        return false;   // 教学版上行模型：只收不发
    }

    @Override
    public void verifyParameters(Map<String, Object> networkSetting,
                                 Map<String, Object> protoSetting) {
        if (!"coap".equals(networkSetting.get("type"))) {
            throw new IllegalArgumentException("CoAP 网关要求 networkSetting.type=coap");
        }
        Object port = networkSetting.get("port");
        if (!(port instanceof Number) || ((Number) port).intValue() != 5683) {
            throw new IllegalArgumentException("CoAP 默认端口 5683，教学版不开放改端口");
        }
    }

    /** 模拟设备 POST 上报：命中资源树则转统一事件上行。 */
    public void onPost(String url, ProtocolEventBus.EventType type, String payload) {
        String path = normalize(url);
        String owner = resourceTree.get(path);
        if (owner == null) {
            throw new IllegalArgumentException("未注册的 CoAP 资源: " + path);
        }
        eventBus.publish(new ProtocolEventBus.ChannelEvent(path, owner, type, payload));
    }

    /** 通道 URL 归一为资源路径（去协议前缀、合并重复斜杠、去尾斜杠）。 */
    static String normalize(String url) {
        String p = url.startsWith("coap://") ? url.substring("coap://".length()) : url;
        p = p.replaceAll("/+", "/");
        return p.endsWith("/") && p.length() > 1 ? p.substring(0, p.length() - 1) : p;
    }

    /** 资源树只读视图（供测试与诊断）。 */
    public Map<String, String> resourceTree() {
        return new LinkedHashMap<>(resourceTree);
    }
}
