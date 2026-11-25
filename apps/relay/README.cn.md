# SuperPI Remote

SuperPI 设备的中继、账号、在线状态与共享记忆服务。

一个自托管的小服务，让多台 SuperPI 安卓设备像同一个助手：保存账号、跟踪在线设备、把一台设备的请求路由到另一台设备上的 bot，并持有各设备共同读写的唯一一份记忆。

## 它做什么

- **账号与设备**：一次部署一个账号，设备 token 可吊销，用短时效配对码或手机间直接扫码入网。
- **在线状态**：由连接生命周期派生，不接受客户端自报。
- **路由**：一台设备请求另一台设备创建、配置或运行它的 bot（`bot.*`、`session.*`）、委派 `super-worker` 协作任务（`worker.*`）、分块传输工作区附件与 `present` 富媒体产物，事件沿同一通道流回。
- **共享记忆**：基于增量协议的 Memory v3 多端同步（`/v3/memory/push`、`/v3/memory/pull`），支持块、实体、事件、关系与提案及实体合流，并提供带冷却窗口的整理互斥租约（`/v3/memory/consolidation-lease`）协调多端整理。
- **上限与错误**：WebSocket 帧最大 8 MiB；单次路由结果最大 4 MiB，超限返回 `payload_too_large` 且不断开连接；未知 `route_error.code` 归一为 `internal`；HTTP JSON 请求体最大 64 KiB（Memory v3 推送 4 MiB），读取超时 30 秒。
- **传输**：自签 CA 下的 HTTPS 与 WSS，配对时固定证书指纹。

它不跑模型、不执行工具、不解释会话内容。

## 目录

```
packages/protocol/    各端共用的线协议与 JSON 测试向量
apps/relay/src/       HTTP 与 WebSocket 服务、配置、入口
apps/relay/test/      服务端测试
apps/relay/scripts/   部署与证书助手脚本
```

## 运行

```bash
pnpm install
SUPERPI_PORT=8443 SUPERPI_HOST=0.0.0.0 pnpm start
```

`GET /v1/health` 返回版本与协议版本。配置来自环境变量：`SUPERPI_HOST`、`SUPERPI_PORT`、`SUPERPI_DATA_DIR`、`SUPERPI_CERT`、`SUPERPI_KEY`、`SUPERPI_CA`、`SUPERPI_PUBLIC_ORIGIN`、`SUPERPI_LAN_ORIGIN`。

生产环境 Docker / systemd 部署与自签证书见 [自部署中继指南](../../docs/user/self-hosting-relay.md)。

## 开发

```bash
pnpm typecheck
pnpm test
pnpm dev
```

## 协议

线协议在 `packages/protocol`：帧类型、错误码、记忆写入操作与 HTTP 载荷；`packages/protocol/vectors` 下的 JSON 向量供各端实现共用，`packages/protocol` 包本身是权威来源。

## 许可证

Apache-2.0，见 [LICENSE](../../LICENSE)。
