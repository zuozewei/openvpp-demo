# openvpp-admin 轻量运营后台

openvpp-demo 配套轻量后台前端脚手架，服务「充电桩需求响应运营实战」专题的业务链路演示：平台发事件 → 场站申报 → 平台分配运营商 → 运营商派单到桩 → 模拟设备执行与遥测 → 效果评估 → 多级分账 → 轻量后台。

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
├── vite.config.js              # Vite 配置（含 /api 代理占位）
├── .env.development            # 开发环境变量（默认开启 Mock）
├── .env.production             # 生产环境变量（默认关闭 Mock）
├── src/
│   ├── main.js                 # 应用入口（Element Plus 全量引入 + 中文语言包）
│   ├── App.vue
│   ├── api/
│   │   ├── request.js          # axios 封装：baseURL / 会话凭证 / 统一错误提示 / Mock 开关
│   │   ├── auth.js             # 登录接口（占位对接后端 /api/v1/operations）
│   │   └── mock/               # Mock 层：演示账号 + 登录 Mock 处理
│   ├── stores/session.js       # 会话状态（localStorage 持久化）
│   ├── router/index.js         # 路由与角色守卫
│   ├── layouts/WorkspaceLayout.vue   # 工作台布局（左侧菜单 + 顶栏）
│   └── views/
│       ├── LoginView.vue               # 登录页（内置 3 个虚构演示账号）
│       └── workspace/
│           ├── WorkspaceHome.vue       # 工作台首页（业务链路 + ECharts 演示图）
│           └── PlaceholderView.vue     # 「建设中」占位页
└── dist/                       # 构建产物（已 gitignore）
```

## 演示账号（全部为虚构数据）

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 平台运营 | `platform_admin` | `Openvpp@2026` |
| 运营商 | `operator_admin` | `Openvpp@2026` |
| 场站运营 | `station_admin` | `Openvpp@2026` |

登录页提供角色快捷填充按钮。登录后按角色进入对应工作台（`/platform`、`/operator`、`/station`），路由守卫会拦截跨角色访问并跳回本角色工作台。

## Mock 开关

由 `import.meta.env.VITE_USE_MOCK` 控制（环境变量 `VITE_USE_MOCK`）：

- `.env.development` 默认 `true`：登录等请求走本地 Mock，无需后端即可演示；
- `.env.production` 默认 `false`：请求走真实后端 `baseURL=/api/v1/operations`。

新增 Mock 接口时在 `src/api/mock/index.js` 的 `mockHandlers` 中按 `` `${METHOD} ${url}` `` 注册即可。

## 后端联调

- axios `baseURL` 固定为 `/api/v1/operations`，登录占位接口为 `POST /auth/login`；
- 开发环境 `vite.config.js` 已将 `/api` 代理到 `http://localhost:8080`，目标地址按实际后端环境调整；
- 请求拦截器自动携带 `Authorization: Bearer <token>`（登录成功后由 Mock 或后端返回的会话凭证）。

## 常用命令

```bash
npm install        # 安装依赖
npm run dev        # 启动开发服务器（默认 5173 端口）
npm run build      # 生产构建（输出 dist/）
npm run preview    # 预览构建产物
```

## 验证记录（2026-10-08，Windows 本机）

- `npm install`：成功（88 个包）；
- `npm run build`：成功（2278 个模块转换，产物输出至 `dist/`）。
