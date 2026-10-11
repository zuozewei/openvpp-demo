package com.openvpp.app.config;

import com.openvpp.settlement.assess.AssessmentRule;
import com.openvpp.settlement.assess.AssessmentService;
import com.openvpp.settlement.assess.BaselineFreezeRegistry;
import com.openvpp.settlement.assess.DirectionalMetering;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 效果评估装配（第 56 篇接口层底座）：冻结登记簿 / 评估规则 / 方向性计量 / 评估服务。
 *
 * 设计取舍（与 ChargeDrOperationsConfig、MonitoringConfig 同口径）：
 * 1. settlement 模块服务均为纯 POJO（无 Spring 注解），由启动模块统一装配，
 *    对象图与 settlement 模块单测一致；
 * 2. 完整度阈值与最少有效样点数走配置（教学默认 0.9 / 3），规则版本与来源
 *    随评估记录留痕，规则文本不对应任何地区真实结算细则。
 */
@Configuration
public class AssessmentConfig {

    @Bean
    public BaselineFreezeRegistry baselineFreezeRegistry() {
        return new BaselineFreezeRegistry();
    }

    @Bean
    public AssessmentRule assessmentRule(
            @Value("${openvpp.assessment.min-completeness:0.9}") double minCompleteness,
            @Value("${openvpp.assessment.min-effective-samples:3}") int minEffectiveSamples) {
        return new AssessmentRule("TEACH-ASSESS-2026.1",
                "教学假设规则：冻结基线均值 + 有效区间方向性积分 + 完整度阈值 + 缺证据转人工复核",
                minCompleteness, minEffectiveSamples);
    }

    @Bean
    public DirectionalMetering directionalMetering() {
        return new DirectionalMetering();
    }

    @Bean
    public AssessmentService assessmentService(BaselineFreezeRegistry baselineFreezeRegistry,
                                               DirectionalMetering directionalMetering) {
        return new AssessmentService(baselineFreezeRegistry, directionalMetering);
    }
}
