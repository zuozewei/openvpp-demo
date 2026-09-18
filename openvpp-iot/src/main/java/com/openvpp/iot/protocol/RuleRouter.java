package com.openvpp.iot.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则路由器 —— 专栏第 48 篇「规则 = 过滤器 + 数据桥」。
 *
 * 一条设备消息按「系统公共规则（最高优先级）+ 租户规则」逐条匹配，
 * 命中即转换出仓到数据桥（教学版：收集桥与 Kafka/TDengine 桥的同构抽象）。
 * 这是「broker 同时是规则路由引擎」的最小内核。
 */
public class RuleRouter {

    /** 数据桥出仓接口（Kafka/TDengine/InfluxDB/JDBC/Pulsar 五选N 的教学抽象）。 */
    public interface DataBridge {
        default String name() {
            return getClass().getSimpleName();
        }

        void route(ProtocolEventBus.ChannelEvent event, String converted);
    }

    /** 单条路由规则：过滤器命中 → 转换 → 出桥。 */
    public static final class RouteRule {
        private final String tenantId;          // null = 系统公共规则（最高优先级）
        private final String field;             // 过滤字段（deviceSn/channelUrl/type）
        private final String equalsValue;       // 等值过滤
        private final DataBridge bridge;

        public RouteRule(String tenantId, String field, String equalsValue, DataBridge bridge) {
            this.tenantId = tenantId;
            this.field = field;
            this.equalsValue = equalsValue;
            this.bridge = bridge;
        }

        boolean match(ProtocolEventBus.ChannelEvent e) {
            String actual;
            switch (field) {
                case "deviceSn":
                    actual = e.deviceSn();
                    break;
                case "channelUrl":
                    actual = e.channelUrl();
                    break;
                case "type":
                    actual = e.type().name();
                    break;
                default:
                    return false;
            }
            return equalsValue == null || equalsValue.equals(actual);
        }
    }

    private final List<RouteRule> rules = new ArrayList<>();

    public void addRule(RouteRule rule) {
        rules.add(rule);
    }

    /** 逐条匹配：系统公共规则先于租户规则（规则顺序即优先级）。 */
    public int dispatch(ProtocolEventBus.ChannelEvent event) {
        int routed = 0;
        for (RouteRule rule : rules) {
            if (rule.match(event)) {
                rule.bridge.route(event, event.payload());
                routed++;
            }
        }
        return routed;
    }

    public List<RouteRule> rules() {
        return new ArrayList<>(rules);
    }
}
