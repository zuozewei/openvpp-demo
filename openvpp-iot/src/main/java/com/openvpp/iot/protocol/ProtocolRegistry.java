package com.openvpp.iot.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议注册表 —— 专栏第 48 篇「诚实的地图」。
 *
 * 两条纪律：
 * <ol>
 *   <li>接入设备必须走 {@link #assertAttachable}：DECLARED 的协议拒绝接入设备
 *       ——「菜单膨胀、后厨不知」的失序从这里被拦住；</li>
 *   <li>引入新协议必须过三判据 {@link #assertIntroducible}：有真实设备、
 *       服务上行采集或下行调控、有维护责任人——没有设备的协议实现是负资产。</li>
 * </ol>
 */
public class ProtocolRegistry {

    private final Map<String, ProtocolSpec> protocols = new LinkedHashMap<>();

    public void register(ProtocolSpec spec) {
        protocols.put(spec.name(), spec);
    }

    public ProtocolSpec get(String name) {
        return protocols.get(name);
    }

    public List<ProtocolSpec> byCategory(ProtocolCategory category) {
        List<ProtocolSpec> result = new ArrayList<>();
        for (ProtocolSpec p : protocols.values()) {
            if (p.category() == category) {
                result.add(p);
            }
        }
        return result;
    }

    /** 全量只读快照。 */
    public List<ProtocolSpec> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(protocols.values()));
    }

    /** 三态统计：菜单 46 项里真正在跑的有多少，一问便知。 */
    public Map<ProtocolStatus, Integer> statusStats() {
        Map<ProtocolStatus, Integer> stats = new LinkedHashMap<>();
        for (ProtocolStatus s : ProtocolStatus.values()) {
            stats.put(s, 0);
        }
        for (ProtocolSpec p : protocols.values()) {
            stats.merge(p.status(), 1, Integer::sum);
        }
        return stats;
    }

    /** 接入校验：DECLARED 协议不许挂设备（声明不是能力）。 */
    public void assertAttachable(String protocolName) {
        ProtocolSpec spec = protocols.get(protocolName);
        if (spec == null) {
            throw new IllegalArgumentException(
                    "协议未注册: " + protocolName + "（先注册并标注落地状态）");
        }
        if (spec.status() == ProtocolStatus.DECLARED) {
            throw new IllegalStateException("协议 " + protocolName
                    + " 仅有声明无实现，拒绝接入设备: " + spec.note());
        }
    }

    /**
     * 引入三判据（专栏第 48 篇第六节）：按顺序问——
     * 有没有真实设备？服务上行还是下行？维护成本谁背？
     *
     * @param hasRealDevices 协议后面有没有真实设备
     * @param maintainerAssigned 有没有明确的维护责任人
     */
    public void assertIntroducible(String protocolName, boolean hasRealDevices,
                                   boolean maintainerAssigned) {
        if (!hasRealDevices) {
            throw new IllegalStateException("拒绝引入 " + protocolName
                    + "：没有真实设备的协议实现是负资产（维护要跟版本、占测试矩阵，收益为零）");
        }
        if (!maintainerAssigned) {
            throw new IllegalStateException("拒绝引入 " + protocolName
                    + "：非标兼容/厂商私有点位/型号定制认证都是随设备存量增长的维护面，须指定责任人");
        }
    }
}
