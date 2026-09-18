package com.openvpp.iot.protocol;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 统一事件母语 —— 专栏第 48 篇「平台内部只有一门母语」。
 *
 * 无论 CoAP、LwM2M、串口还是 Modbus，网关侧全部收敛成四类事件
 * （数据/状态/告警/命令回执）经分发器上行——协议差异被消灭在网关层，
 * VPP 业务只消费归一化事件，不感知协议。
 */
public class ProtocolEventBus {

    /** 事件类型：数据/状态/告警/命令回执 */
    public enum EventType {
        BUSS_DATA, STATUS, EVENT, CONFIRM
    }

    /** 协议侧统一事件：通道 + 设备 + 类型 + 载荷。 */
    public static final class ChannelEvent {
        private final String channelUrl;
        private final String deviceSn;
        private final EventType type;
        private final String payload;

        public ChannelEvent(String channelUrl, String deviceSn, EventType type, String payload) {
            this.channelUrl = channelUrl;
            this.deviceSn = deviceSn;
            this.type = type;
            this.payload = payload;
        }

        public String channelUrl() {
            return channelUrl;
        }

        public String deviceSn() {
            return deviceSn;
        }

        public EventType type() {
            return type;
        }

        public String payload() {
            return payload;
        }
    }

    /** 事件消费者。 */
    public interface Consumer {
        void onEvent(ChannelEvent event);
    }

    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer consumer) {
        consumers.add(consumer);
    }

    /** 网关上行唯一入口：任何协议的任何报文，最终都从这扇门进来。 */
    public void publish(ChannelEvent event) {
        for (Consumer c : consumers) {
            c.onEvent(event);
        }
    }

    public List<Consumer> consumers() {
        return Collections.unmodifiableList(consumers);
    }
}
