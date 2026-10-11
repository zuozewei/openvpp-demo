package com.openvpp.app.config;

import com.openvpp.resource.org.OrgRegistryService;
import com.openvpp.settlement.assess.AssessmentService;
import com.openvpp.settlement.charge.SettlementService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多级分账装配（第 57 篇接口层底座）：结算服务 Bean 装配。
 *
 * 设计取舍（与 AssessmentConfig、ChargeDrOperationsConfig 同口径）：
 * 1. settlement 模块的结算服务为纯 POJO（无 Spring 注解），由启动模块统一装配，
 *    对象图与 settlement 模块单测一致（AssessmentService + OrgRegistryService 注入）；
 * 2. 补偿单价与考核扣款为生成请求输入（教学假设口径，不内置考核公式），
 *    分账比例随请求快照传入，构造即校验比例总和为 1；
 * 3. 运营时间一律经 Clock Bean 取（operationsClock），业务口径不变。
 */
@Configuration
public class SettlementConfig {

    @Bean
    public SettlementService settlementService(AssessmentService assessmentService,
                                               OrgRegistryService orgRegistryService) {
        return new SettlementService(assessmentService, orgRegistryService);
    }
}
