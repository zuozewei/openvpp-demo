package com.openvpp.iot;

import com.openvpp.iot.protocol.CoapResourceTreeGateway;
import com.openvpp.iot.protocol.DefaultProtocolRegistry;
import com.openvpp.iot.protocol.GatewayRunner;
import com.openvpp.iot.protocol.ProtocolCategory;
import com.openvpp.iot.protocol.ProtocolEventBus;
import com.openvpp.iot.protocol.ProtocolRegistry;
import com.openvpp.iot.protocol.ProtocolSpec;
import com.openvpp.iot.protocol.ProtocolStatus;
import com.openvpp.iot.protocol.RuleRouter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 第 48 篇多协议接入地图单测：三态统计 / DECLARED 拒绝接入 / 引入三判据 /
 * 通道 URL→资源树 / 统一事件母语 / 规则路由过滤器+桥 / CoAP 参数校验。
 */
class ProtocolMapTest {

    @Test
    void 三态统计菜单与后厨一眼分明() {
        ProtocolRegistry registry = DefaultProtocolRegistry.create();
        Map<ProtocolStatus, Integer> stats = registry.statusStats();
        assertTrue(stats.get(ProtocolStatus.IN_USE) >= 8, "在用协议至少 8 项");
        assertTrue(stats.get(ProtocolStatus.DECLARED) >= 6, "纯声明至少 6 项");
        assertTrue(stats.get(ProtocolStatus.PREPARED) >= 3, "预制待命至少 3 项");
        assertEquals(registry.snapshot().size(),
                stats.get(ProtocolStatus.IN_USE) + stats.get(ProtocolStatus.PREPARED)
                        + stats.get(ProtocolStatus.DECLARED), "三态总量守恒");
    }

    @Test
    void 声明协议拒绝挂设备() {
        ProtocolRegistry registry = DefaultProtocolRegistry.create();
        assertThrows(IllegalStateException.class,
                () -> registry.assertAttachable("HJ212"),
                "仅有声明无实现的协议，拒绝接入设备");
        assertThrows(IllegalArgumentException.class,
                () -> registry.assertAttachable("NOT_REGISTERED"),
                "未注册协议直接拒绝");
        // 在用与预制可以挂
        assertDoesNotThrow(() -> registry.assertAttachable("MQTT"));
        assertDoesNotThrow(() -> registry.assertAttachable("CoAP"));
    }

    @Test
    void 引入协议三判据() {
        ProtocolRegistry registry = DefaultProtocolRegistry.create();
        // 没有真实设备：拒绝
        assertThrows(IllegalStateException.class,
                () -> registry.assertIntroducible("ONVIF", false, true),
                "没有设备的协议实现是负资产");
        // 有设备但无维护责任人：拒绝
        assertThrows(IllegalStateException.class,
                () -> registry.assertIntroducible("ONVIF", true, false),
                "非标兼容是随存量增长的维护面，须指定责任人");
        assertDoesNotThrow(() -> registry.assertIntroducible("ONVIF", true, true));
    }

    @Test
    void 按领域分类查询协议() {
        ProtocolRegistry registry = DefaultProtocolRegistry.create();
        List<ProtocolSpec> power = registry.byCategory(ProtocolCategory.POWER);
        assertEquals(3, power.size(), "电力规约三态各一：点位表在用/预制、698 仅声明");
        assertTrue(registry.byCategory(ProtocolCategory.VIDEO).stream()
                .allMatch(p -> p.status() == ProtocolStatus.DECLARED), "视频类全是纯声明");
    }

    @Test
    void 通道URL递归映射成CoAP资源树() {
        ProtocolEventBus bus = new ProtocolEventBus();
        CoapResourceTreeGateway gateway = new CoapResourceTreeGateway(bus);
        Map<String, String> channels = new LinkedHashMap<>();
        channels.put("coapendpoints/home/room1/sensor1", "BUSS_DATA");
        channels.put("coapendpoints/home/room1/sensor2", "BUSS_DATA");
        gateway.registerChannel("dev-001", channels);
        assertEquals(2, gateway.resourceTree().size());
        // 注销设备时其资源一并移除
        gateway.unRegisterChannel("dev-001");
        assertEquals(0, gateway.resourceTree().size(), "通道注销同步清理资源树");
    }

    @Test
    void CoAP上行收敛为统一事件母语() {
        List<ProtocolEventBus.ChannelEvent> received = new ArrayList<>();
        ProtocolEventBus bus = new ProtocolEventBus();
        bus.subscribe(received::add);
        CoapResourceTreeGateway gateway = new CoapResourceTreeGateway(bus);
        Map<String, String> channels = new LinkedHashMap<>();
        channels.put("coapendpoints/home/room1/sensor1", "BUSS_DATA");
        gateway.registerChannel("dev-001", channels);

        gateway.onPost("coap://coapendpoints/home/room1/sensor1",
                ProtocolEventBus.EventType.BUSS_DATA, "{\"temp\":26.1}");
        assertEquals(1, received.size());
        assertEquals("dev-001", received.get(0).deviceSn());
        assertEquals(ProtocolEventBus.EventType.BUSS_DATA, received.get(0).type());
        // 未注册资源被拒：资源树是唯一入口
        assertThrows(IllegalArgumentException.class,
                () -> gateway.onPost("coapendpoints/unknown/path",
                        ProtocolEventBus.EventType.BUSS_DATA, "{}"));
    }

    @Test
    void 规则路由过滤器加数据桥() {
        List<String> kafkaSink = new ArrayList<>();
        List<String> tdSink = new ArrayList<>();
        RuleRouter router = new RuleRouter();
        // 系统公共规则（tenantId=null）：全部数据进 Kafka——最高优先级
        router.addRule(new RuleRouter.RouteRule(null, "deviceSn", null,
                (e, converted) -> kafkaSink.add(converted)));
        // 租户规则：空调设备数据进时序库
        router.addRule(new RuleRouter.RouteRule("t1", "deviceSn", "ac-001",
                (e, converted) -> tdSink.add(converted)));

        ProtocolEventBus.ChannelEvent acEvent = new ProtocolEventBus.ChannelEvent(
                "/ac/1", "ac-001", ProtocolEventBus.EventType.BUSS_DATA, "26.0");
        ProtocolEventBus.ChannelEvent pvEvent = new ProtocolEventBus.ChannelEvent(
                "/pv/1", "pv-002", ProtocolEventBus.EventType.BUSS_DATA, "180.5");

        assertEquals(2, router.dispatch(acEvent), "公共规则 + 租户规则都命中");
        assertEquals(1, router.dispatch(pvEvent), "只命中公共规则");
        assertEquals(2, kafkaSink.size(), "Kafka 桥收全量");
        assertEquals(1, tdSink.size(), "时序库桥只收空调");
    }

    @Test
    void CoAP网关参数校验() {
        GatewayRunner gateway = new CoapResourceTreeGateway(new ProtocolEventBus());
        Map<String, Object> network = new LinkedHashMap<>();
        network.put("type", "coap");
        network.put("port", 5683);
        assertDoesNotThrow(() -> gateway.verifyParameters(network, new LinkedHashMap<>()));
        network.put("port", 5684);
        assertThrows(IllegalArgumentException.class,
                () -> gateway.verifyParameters(network, new LinkedHashMap<>()),
                "教学版不开放改端口");
        // 上行模型：下行命令返回不支持
        assertFalse(gateway.onCommand("dev-001", "/x", "cmd"), "CoAP 教学版只收不发");
    }
}
