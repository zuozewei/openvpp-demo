package com.openvpp.app.controller;

import com.openvpp.app.orchestration.DemoRunResult;
import com.openvpp.app.orchestration.ParkResponseOrchestrator;
import com.openvpp.app.orchestration.TaskInProgressException;
import com.openvpp.app.persistence.ResponseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 园区需求响应贯穿案例入口 —— 闭环触发 + 争议更正 + 业务查询。
 *
 * 触发：POST /api/v1/demo/run?responseId=run-001&path=NORMAL
 * 更正：POST /api/v1/demo/dispute?responseId=run-003&correctedActualKw=380（结算后独立入口）
 * 查询：任务 /api/v1/tasks、指令 /api/v1/instructions、
 *       基线 /api/v1/baselines/{responseId}、账单 /api/v1/bills
 * 幂等：同一 responseId 重复 POST 不重复执行、不重复出账
 *      （唯一例外：任务已结算后再以 path=DISPUTED 触发 = 争议更正请求）。
 * 重置：POST /api/v1/demo/reset（清理演示数据，便于再次运行）。
 */
@RestController
@RequestMapping("${openvpp.api-prefix:/api/v1}")
public class ParkResponseController {

    private final ParkResponseOrchestrator orchestrator;
    private final ResponseRepository repo;

    public ParkResponseController(ParkResponseOrchestrator orchestrator, ResponseRepository repo) {
        this.orchestrator = orchestrator;
        this.repo = repo;
    }

    /** 触发一遍闭环。path: NORMAL / DEGRADED / DISPUTED */
    @PostMapping("/demo/run")
    public DemoRunResult run(@RequestParam String responseId,
                             @RequestParam(defaultValue = "NORMAL") String path,
                             @RequestParam(defaultValue = "600") BigDecimal declaredKwh,
                             @RequestParam(defaultValue = "900") BigDecimal targetKw) {
        return orchestrator.run(responseId, path, declaredKwh, targetKw);
    }

    /**
     * 结算后争议更正的独立入口：对已结算任务按更正计量（第 2 时段实测修正为
     * correctedActualKw，默认 380）重算结算四量与分摊，生成下一账期版本的全套
     * 更正账单（SETTLE/PENALTY/PLATFORM_CUT/SHARE/CORRECTION）；历史版本保留，
     * 支持同一任务多轮更正（V2、V3…），非零差额全额传导到服务费与分摊。
     *
     * correctionRequestId（可选，4-64 位）为纠偏请求幂等键：同一请求重复提交
     * （含并发）返回原版本结果、不重复出账；不传则每次视为新请求自动登记。
     * 并发多个不同请求按版本号原子分配，逐版本独立留档互不覆盖。
     */
    @PostMapping("/demo/dispute")
    public DemoRunResult dispute(@RequestParam String responseId,
                                 @RequestParam(defaultValue = "380") BigDecimal correctedActualKw,
                                 @RequestParam(required = false) String correctionRequestId) {
        return orchestrator.dispute(responseId, correctedActualKw, correctionRequestId);
    }

    /**
     * 清理演示数据（重置后可用同一 responseId 再次运行）。
     * 库表与内存运行态（容量预占台账、内存指令仓库）必须一起清——
     * 只清库不清内存会残留旧预占，挤占剩余能力，再运行正常案例误报缺口。
     */
    @PostMapping("/demo/reset")
    public Map<String, Object> reset() {
        int t = repo.deleteAll();
        orchestrator.resetRuntimeState();
        return Map.of("cleared", t, "note", "演示数据与运行状态已清理，可再次运行");
    }

    /**
     * 入参约束违规统一 400：派生指令编号超长（responseId 超出 4-50 位约束）、
     * 非法 path、负数申报等在校验层先行拒绝并给出原因，不再落到落库 500。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * 同键任务认领竞争超限统一 409：并发窗口内任务正在处理（正常执行时长远小于
     * 认领等待窗口，仅数据库锁等待超时等 pathological 场景触发），调用方稍后重试。
     */
    @ExceptionHandler(TaskInProgressException.class)
    public ResponseEntity<Map<String, Object>> inProgress(TaskInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() {
        return repo.listTasks();
    }

    @GetMapping("/instructions")
    public List<Map<String, Object>> instructions(@RequestParam(required = false) String responseId) {
        return repo.listInstructions(responseId);
    }

    @GetMapping("/baselines")
    public List<Map<String, Object>> baselines(@RequestParam String responseId) {
        return repo.listBaselines(responseId);
    }

    @GetMapping("/bills")
    public List<Map<String, Object>> bills(@RequestParam(required = false) String responseId) {
        return repo.listBills(responseId);
    }
}
