# Live Monitor Vue 3 前端工程

基于 Vue 3 (Composition API / `<script setup>`) + Vue CLI + Vue Router 4 重构的现代化工业级监控前端，支持完全离线部署。

## 技术特性

1. **完全本地离线部署**：
   - 所有依赖、样式、SVG 矢量图标均为本地静态资源，零外部 CDN 网络依赖；
   - 当前使用根路径 publicPath 和 hash 路由，通过 Spring Boot 或 HTTP 服务提供页面与 API；不支持直接双击 HTML 文件运行。
2. **工业级编码规范**：
   - 采用 Vue 3 官方推荐的 Composition API (`<script setup>`)；
   - 模块化设计：`src/api/`、`src/router/`、`src/store/`、`src/views/`、`src/components/`；
   - 扁平现代化的科技蓝设计语言，清晰的状态指示色与交互反馈。
3. **功能设计完全对齐后端**：
   - **监控总览**：汇总指标、多维状态过滤、服务列表、本地系统资源监控、活动流水；
   - **主机维护**：SSH 主机资产、在线率、CPU/内存/磁盘阈值策略配置与即时测试；
   - **添加 / 编辑服务**：
     - 支持 Web、API、进程检测、端口、Redis、ZooKeeper、MySQL、Oracle、PostgreSQL、通用 JDBC 及**数据发送**；
     - **数据发送服务项**：支持选择已维护好的数据库监控、编写查询 SQL、**预览查询结果（数据表格展示，最多 10 条限制）**、**适合手机短信显示的列数限制（最多 4 列，推荐 2~3 列，支持列别名）**、手机短信效果实时模拟、即时测试与定时监控；
   - **数据发送工作台**：无多余菜单的简洁工作台，支持快速选择数据库、写 SQL、表格预览、短信列限制与即时发送；
   - **告警配置**：短信 SMS、邮件、Webhook 渠道管理与告警组关联；
   - **告警审计**：全量告警通知流水审计与状态筛选；
   - **用户维护**：运维及管理员账号管理与密码重置；
   - **用户登录与权限控制**。

## 运行与构建

### 1. 本地开发（热重载）
```bash
cd live-monitor-java/frontend
npm run serve
```
默认运行在 `http://localhost:8080/`，通过代理自动将 `/api` 请求转发至后端的 `http://localhost:8000`。

### 2. 本地离线生产打包
```bash
npm run build
```
打包产物输出至 `dist/` 目录。

### 3. 一键同步部署至 Spring Boot 静态资源
```bash
npm run deploy
```
打包并自动将产物复制至 `src/main/resources/static`，后端重新打包或启动即可直接访问 Vue 3 界面。

## 告警修复合并与功能补齐（1.0.1）

feature/vue3-frontend 已合并持久化告警修复。新增告警任务与重试、外部项目与消息审计、主机指标历史和进程维护、个人密码页面；补齐服务告警阈值、UNKNOWN、多组绑定、认证与断言、跨库查询与映射、渠道与组编辑、审计时间筛选和用户删除。

修复请求字段与后端不一致、发送业务失败被显示为成功、主机探测和密码接口错误、长弹窗无法点击保存等问题。完整对照见 ../VUE3_FUNCTIONAL_PARITY.txt。

从 Java 工程目录执行 mvn verify，会自动 npm ci、构建 Vue、同步静态资源，再运行 Java 验证和打包。构建环境需要 Node/npm 与 JDK/Maven；运行 JAR 不需要 Node。仅在已构建前端且只调试后端时使用 -DskipFrontend=true。

npm run build 先输出 dist，再同步至 Spring Boot 静态目录；不会清空旧版参考页面。npm run deploy 是相同行为。不要只复制 Vue 源文件而跳过构建。

浏览器回归：
npm ci
npx playwright install chromium
npm run build
npm run test:e2e

可通过 PLAYWRIGHT_CHROMIUM_EXECUTABLE 指定已安装的 Chromium。测试只访问本地服务，若系统代理劫持 localhost，请在运行测试的终端为 localhost/127.0.0.1 配置 NO_PROXY。浏览器用例拦截 API 来验证页面流程和真实请求形状；后端 H2/HTTP/强杀恢复由 Maven 集成测试验证，真实 SSH/第三方消息网关仍需部署环境验证。