package com.openvpp.app.service;

import com.openvpp.iot.simulate.ChargePileDownlinkChannel;
import com.openvpp.iot.simulate.CommandReceipt;
import com.openvpp.iot.simulate.FaultInjectionProfile;
import com.openvpp.iot.simulate.SimulatedChargePileChannel;
import com.openvpp.iot.thingmodel.ThingModel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 演示充电桩设备院子 —— 分级派单链路（第 54 篇接口层底座）的模拟设备侧装配。
 *
 * 职责：
 * <ol>
 *   <li>按桩建档：计划二级行的 subjectId（快照资源标识，如 ev-cs-station-01）首次
 *       下发时在本院子登记一台模拟设备（ SimulatedChargePileChannel 多实例，
 *       一资源一实例，实例间设备状态互不串扰）；</li>
 *   <li>路由下行：实现 {@link ChargePileDownlinkChannel}，按资源标识把绝对目标功率
 *       投递到对应模拟设备，供 {@code ChargePileCommandService} 装配 ——
 *       InstructionService 的日志型下行回调保持不动，两链路并存不互相替换；</li>
 *   <li>教学故障注入：{@link #applyProfile} 仅供测试与演示切换设备档案
 *       （如失联场景），生产路径不存在"运行期改故障"的操作。</li>
 * </ol>
 *
 * 物模型口径：申报快照的资源是场站级聚合充电资源（额定 2400/720 kW、基线 900/300 kW），
 * 远超单桩物模型 charger-pile-v1 的 120 kW 量程 —— 院子为站级聚合教学设备单独建模
 * charger-station-agg-v1（power/targetPower 0~2400 kW，覆盖两个演示场站的额定上限，
 * 数值全为教学设定）；真实接入时以"协议适配实现 ChargePileDownlinkChannel"替换本院子，
 * 调用方零改动。
 *
 * 教学实现单 JVM 内存版（synchronized 串行化），设备状态随进程生命周期。
 */
public class ChargePileDeviceYard implements ChargePileDownlinkChannel {

    /** 站级聚合教学物模型标识（一资源一实例的模拟设备共用本模型） */
    public static final String STATION_AGG_MODEL_ID = "charger-station-agg-v1";

    private final ThingModel stationAggregateModel;
    private final Map<String, SimulatedChargePileChannel> devices = new LinkedHashMap<>();

    public ChargePileDeviceYard() {
        this.stationAggregateModel = buildStationAggregateModel();
    }

    /**
     * 下发绝对目标功率（契约见 {@link ChargePileDownlinkChannel}）：按资源标识路由到
     * 本院登记的模拟设备；未建档的资源先建档再投递（建档即首次触达设备）。
     */
    @Override
    public synchronized Optional<CommandReceipt> sendTargetPower(String resourceId, BigDecimal targetKw,
                                                                 String commandNo) {
        return channelOf(resourceId).sendTargetPower(resourceId, targetKw, commandNo);
    }

    /**
     * 切换指定设备的故障注入档案。【教学用途】仅供测试与演示构造异常场景
     * （如失联无回执进入核查），设备不存在时先建档再注入；生产路径无此操作。
     */
    public synchronized void applyProfile(String resourceId, FaultInjectionProfile profile) {
        channelOf(resourceId).applyProfile(profile);
    }

    /** 恢复指定设备为健康档案（演示运行 finally 语义，保证多次编排可干净复跑） */
    public synchronized void resetProfile(String resourceId) {
        channelOf(resourceId).applyProfile(FaultInjectionProfile.none());
    }

    /** 指定设备当前生效目标（kW）；未建档即建档（教学视口，仅演示与测试使用） */
    public synchronized double currentTargetKw(String resourceId) {
        return channelOf(resourceId).currentTargetKw();
    }

    /** 已建档设备清单视图（资源标识 + 当前目标 + 档案），供演示与测试观测 */
    public synchronized List<Map<String, Object>> listDevices() {
        List<Map<String, Object>> views = new ArrayList<>();
        for (Map.Entry<String, SimulatedChargePileChannel> entry : devices.entrySet()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("resourceId", entry.getKey());
            body.put("modelId", STATION_AGG_MODEL_ID);
            body.put("currentTargetKw", entry.getValue().currentTargetKw());
            body.put("offline", entry.getValue().currentProfile().isOffline());
            views.add(body);
        }
        return List.copyOf(views);
    }

    // ---------------- 内部 ----------------

    private SimulatedChargePileChannel channelOf(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("桩资源标识不能为空");
        }
        return devices.computeIfAbsent(resourceId, id -> new SimulatedChargePileChannel(
                id, stationAggregateModel, FaultInjectionProfile.none(),
                SimulatedChargePileChannel.DEFAULT_SEED + id.hashCode(),
                System::currentTimeMillis));
    }

    /**
     * 站级聚合教学物模型：power（遥测，0~2400 kW，只读）+ targetPower（下发目标，
     * 0~2400 kW，可写）+ 失联/故障事件 + setPower 服务，形态对齐 charger-pile-v1，
     * 量程按演示场站额定上限放宽（数值全为教学设定）。
     */
    private static ThingModel buildStationAggregateModel() {
        ThingModel.Property power = new ThingModel.Property();
        power.setIdentifier("power");
        power.setName("聚合充电功率");
        power.setDataType("DOUBLE");
        power.setUnit("kW");
        power.setMin(0.0);
        power.setMax(2400.0);
        power.setWritable(false);

        ThingModel.Property targetPower = new ThingModel.Property();
        targetPower.setIdentifier("targetPower");
        targetPower.setName("聚合目标功率设定");
        targetPower.setDataType("DOUBLE");
        targetPower.setUnit("kW");
        targetPower.setMin(0.0);
        targetPower.setMax(2400.0);
        targetPower.setWritable(true);

        ThingModel.Event offline = new ThingModel.Event();
        offline.setIdentifier("offline");
        offline.setName("通信失联");

        ThingModel.Service setPower = new ThingModel.Service();
        setPower.setIdentifier("setPower");
        setPower.setName("设定聚合充电功率");
        setPower.setOutputProperty("targetPower");

        ThingModel model = new ThingModel();
        model.setModelId(STATION_AGG_MODEL_ID);
        model.setName("站级聚合充电桩组物模型 v1（教学）");
        model.setProperties(List.of(power, targetPower));
        model.setEvents(List.of(offline));
        model.setServices(List.of(setPower));
        return model;
    }
}
