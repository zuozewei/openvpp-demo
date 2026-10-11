# openvpp-admin 轻量运营后台

openvpp-demo 配套轻量后台前端，服务「充电桩需求响应运营实战」专题的业务链路演示：平台发事件 → 场站申报 → 平台分配运营商 → 运营商派单到桩 → 模拟设备执行与遥测 → 效果评估 → 多级分账 → 轻量后台。

> 本目录为独立前端工程，**未加入 Maven reactor**，不参与后端构建；全部界面文案为中文，演示账号与数据均为虚构。

## 技术栈与锁定版本

以 `npm install` 后 `package-lock.json` 实装为准（2026-10-08 实测）：

| 依赖 | 版本 |
| --- | --- |
| Node.js | v24.12.0 |
| npm | 11.6.2 |
| Vue | 3.5.43（script setup 语法） |
| Vite | 7.3.7 |
| @vitejs/plugin-vue | 6.0.9 |
| Element Plus | 2.14.7 |
| @element-plus/icons-vue | 2.3.2 |
| vue-router | 4.6.4 |
| axios | 1.20.0 |
| ECharts | 6.1.0 |

## 目录结构

```
openvpp-admin/
├── index.html                  # 入口 HTML
├── vite.config.js              # Vite 配置（/api 代理，目标可用 VITE_API_TARGET 覆盖）
├── .env.development            # 开发环境变量（默认开启 Mock）
├── .env.production             # 生产环境变量（默认关闭 Mock）
├── src/
│   ├── main.js                 # 应用入口（Element Plus 全量引入 + 中文语言包）
│   ├── App.vue
│   ├── api/
│   │   ├── request.js          # axios 封装：baseURL / 会话凭证 / 401 跳登录 / 统一错误提示 / Mock 开关
│   │   ├── index.js            # 全部运营接口封装（与后端 Operations*Controller 一一对应）
│   │   ├── dicts.js            # 状态字典：枚举 code → 中文标签与标签类型
│   │   ├── auth.js             # 登录接口
│   │   └── mock/               # Mock 层：全链路内存状态机（离线演示）
│   ├── utils/format.js         # 时间/功率/电量/金额格式化与本地时间转换
│   ├── stores/session.js       # 会话状态（localStorage 持久化）
│   ├── router/index.js         # 路由与角色守卫（操作审计页仅平台）
│   ├── components/DictTag.vue  # 枚举状态标签组件
│   ├── layouts/WorkspaceLayout.vue   # 工作台布局（左侧菜单 + 顶栏 + 全局口径提示）
│   └── views/
│       ├── LoginView.vue               # 登录页（内置 3 个虚构演示账号）
│       └── workspace/
│           ├── WorkspaceHome.vue       # 工作台首页（业务链路 + ECharts 演示图）
│           ├── EventsView.vue          # 事件管理（列表/新建/发布/授权/结束）
│           ├── EventDetailView.vue     # 事件详情（八段全链时间线 + 各阶段证据表）
│           ├── DeclarationsView.vue    # 聚合申报（提交/确认/撤回，按角色显隐）
│           ├── DispatchView.vue        # 分级派单（构建/确认/下发 + 缺口显性 + 指令证据表）
│           ├── MonitorView.vue         # 运行监测（目标/实际曲线 + 样点质量 + 告警确认）
│           ├── AssessmentView.vue      # 效果评估（申请/复核，核定与看板估算两列分列）
│           ├── SettlementView.vue      # 多级分账结算（四量/分账明细/版本历史/更正/模拟付款）
│           └── AuditView.vue           # 操作审计（平台专属）
├── verification/               # 第 58 篇交付验证实录（curl 全链脚本与转录、UI 走查截图）
└── dist/                       # 构建产物（已 gitignore）
```

## 演示账号（全部为虚构数据）

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 平台运营 | `platform_admin` | `Openvpp@2026` |
| 运营商 | `operator_admin` | `Openvpp@2026` |
| 场站运营 | `station_admin` | `Openvpp@2026` |

登录页提供角色快捷填充按钮。登录后按角色进入对应工作台（`/platform`、`/operator`、`/station`），路由守卫会拦截跨角色访问并跳回本角色工作台。

## 页面与接口对照

全部接口位于 `/api/v1/operations`（axios baseURL 固定），写操作均服务端留审计、身份取服务端会话、拒绝原因经响应 `message` 原样弹出：

| 页面 | 接口（方法 路径） | 要点 |
| --- | --- | --- |
| 登录/会话 | `POST /auth/login`、`GET /auth/me` | 401 自动清会话回登录页 |
| 事件管理 | `POST /events`、`POST /events/{id}/publish`、`/finish`、`/participations`、`GET /events` | 新建/发布/授权/结束按角色显隐；时间链校验失败原因原样展示 |
| 事件详情 | `GET /events/{id}` + 申报/计划/指令/执行/评估/结算各段独立加载 | 八段时间线聚合全链状态；单段加载失败（404/越权）不影响其余段落，错误按段展示 |
| 申报管理 | `POST /declarations`、`POST /declarations/{id}/confirm`、`/withdraw`、`GET /declarations` | 提交（场站）/确认（运营商）/撤回（场站）；requestId 幂等键自动生成；容量/截止/快照/授权守门拒绝原因原样展示 |
| 派单管理 | `POST /plans`、`POST /plans/{id}/confirm`、`/send`、`GET /plans`、`GET /plans/{id}`、`GET /commands`、`POST /plans/demo-runs` | 缺口 &gt; 0 红色显性「禁止确认/下发」；指令证据表发送时标/回执时标/拒绝原因分列；「已下发 ≠ 设备已执行」口径常驻提示 |
| 运行监测 | `GET /monitoring/executions`、`GET /monitoring/alerts`、`POST /monitoring/alerts/{id}/confirm`、`POST /monitoring/demo-runs` | 目标/实际曲线（ECharts）；SIMULATED 来源、迟到/重复/乱序样点显式标记；缺失段不补 0（缺口标注）；回执/到位/连续达标三列分列；告警确认留痕 |
| 效果评估 | `POST /assessments`、`POST /assessments/{id}/review`、`GET /assessments`、`POST /assessments/demo-runs` | 申请（运营商）/复核（平台）；核定响应量（结算依据）与看板估算（不作核定依据）两列分列；待复核原因逐条呈现 |
| 结算管理 | `POST /settlements`、`POST /settlements/{id}/confirm`、`/payments`、`/corrections`、`GET /settlements`、`GET /settlements/{id}` | 四量（毛额/扣款/净实收/分账）；分账明细合计校验；账单版本历史与更正链完整呈现；付款登记显式标注「模拟付款（渠道 SIMULATED，不接真实资金通道）」 |
| 操作审计 | `GET /audit-logs` | 平台专属菜单项；SUCCESS/REJECTED 分列 |

## 演示编排入口

派单/监测/评估三页为平台角色提供「演示编排」按钮（对应后端 `POST /plans/demo-runs`、`POST /monitoring/demo-runs`、`POST /assessments/demo-runs`），可选 9 种故障注入场景（健康/削峰偏高/填谷偏低/零目标/迟到/缺口/重复/乱序/失联），一键造出端到端演示数据；运营商/场站在编排产物上继续真实角色操作（确认、下发、申请评估、告警确认）。

## Mock 开关

由 `import.meta.env.VITE_USE_MOCK` 控制（环境变量 `VITE_USE_MOCK`）：

- `.env.development` 默认 `true`：全部请求走本地 Mock 内存状态机，无需后端即可离线演示全链路；
- `.env.production` 默认 `false`：请求走真实后端 `baseURL=/api/v1/operations`。

Mock 层（`src/api/mock/index.js`）以内存状态机模拟后端：预置一个已发布待申报事件与一个全链走完事件（含结算更正与模拟付款），写操作带简化版守卫（截止/容量/快照版本/缺口/幂等键），响应字段名与后端响应视图严格一致。

## 后端联调

- axios `baseURL` 固定为 `/api/v1/operations`，登录接口 `POST /auth/login`；
- 开发环境 `vite.config.js` 已将 `/api` 代理到 `http://localhost:8080`，可用环境变量 `VITE_API_TARGET` 覆盖目标（如独立验证实例 `http://localhost:8081`）；
- 请求拦截器自动携带 `Authorization: Bearer <token>`；
- 响应 401（会话缺失/越角色）自动清理会话并跳回登录页；其余错误（400/404/409）按响应 `message` 原样弹出，页面不掩盖服务端拒绝原因。

## 常用命令

```bash
npm install        # 安装依赖
npm run dev        # 启动开发服务器（默认 5173 端口）
npm run build      # 生产构建（输出 dist/）
npm run preview    # 预览构建产物
```

## 验证记录

### 2026-10-08（脚手架基线）

- `npm install`：成功（88 个包）；
- `npm run build`：成功（2278 个模块转换，产物输出至 `dist/`）。

### 2026-10-12（第 58 篇运营工作台全页面交付）

**构建**：`npm run build` 通过（全部 16 个页面/组件分包构建成功）。

**后端联调实录**（`verification/58-walkthrough.sh` → `verification/curl-transcript.txt`）：

- 环境：`openvpp-app`（b5d2d8f）独立实例（`--server.port=8081` + 独立 H2 数据目录），3 演示账号共 58 步全链调用，期望状态全部命中（200 成功、401/400/404/409 守门均按预期返回）；
- 服务端边界：错误口令 401、运营商建事件 401、场站查审计 401、场站越权确认 401；
- 事件链：建/发/授权 200；场站申报 500 kW 200、同 requestId 幂等返回原单、超额 409（容量守门）、未授权场站 409、未登记快照版本 400；
- 派单链：截止守门等待后构建计划 200、分配行/子计划/版本历史齐全；运营商确认 200、重复确认幂等 200、下发 200、重复下发幂等拦截、指令证据发送/回执时标分列；
- 监测链：LATE_STREAM / PEAK_BIAS / OFFLINE 编排 200；迟到样点质量标记与证据资格列可查；方向超差告警产生并被运营商确认 200；失联指令 NO_RECEIPT 进核查；
- 评估链：MISSING_POINTS 编排产生 MANUAL_REVIEW 记录（待复核原因：完整度不足）、运营商对已结束事件申请评估幂等返回原记录、平台复核留痕 200；对未结束事件申请 409；
- 结算链：核定通过评估生成结算单 200、同 requestId 幂等、比例和≠1 400、转复核评估 409、扣款超毛额 409；确认 v1 200 → 更正生成 v2 200（历史版本留存）→ 确认 v2 200 → 已付款后更正被 409 拦截（防账实不符）→ 模拟付款登记 200（渠道 SIMULATED）；结算单详情历史版本/更正链/模拟付款记录齐备；场站视角仅见本场站单据；
- 审计：`GET /audit-logs` 全量留痕可查（SUCCESS/REJECTED 分列）。

**UI 走查**（Playwright，`verification/ui-walkthrough.py` → `verification/screenshots/`，Mock 离线冒烟 → `verification/screenshots-mock/`）：

- 3 演示账号登录 → 各角色工作台/业务页全量截图（登录、事件列表、事件详情八段时间线、派单指令证据、监测曲线与告警、评估两列分列、结算详情抽屉、审计）；
- 浏览器控制台零报错；Mock 模式（5175，无后端）同样走查通过，离线演示可用。

**口径核验**：页面权限仅作操作提示（全局提示条常驻）；「已下发 ≠ 设备已执行」在派单/监测/事件详情三处显式标注；指令状态、回执、达标分列展示；服务端拒绝原因均经 `message` 原样弹出；历史账单版本、更正链、待复核状态、模拟付款标注全部显性呈现。
