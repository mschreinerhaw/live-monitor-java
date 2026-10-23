# Live Monitor Vue 3 前端工程

基于 Vue 3 (Composition API / `<script setup>`) + Vue CLI + Vue Router 4 重构的现代化工业级监控前端，支持完全离线部署。

## 技术特性

1. **完全本地离线部署**：
   - 所有依赖、样式、SVG 矢量图标均为本地静态资源，零外部 CDN 网络依赖；
   - `publicPath: './'` 相对路径打包，可直接通过文件协议、任意 Nginx 二级路径或 Spring Boot 内置容器部署。
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
