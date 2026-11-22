# SuperPI Remote

SuperPI 设备的中继、账号、在线状态与共享记忆服务。

一个自托管的小服务，让多台 SuperPI 安卓设备像同一个助手：保存账号、跟踪在线设备、把一台设备的请求路由到另一台设备上的 bot，并持有各设备共同读写的唯一一份记忆。

## 它做什么

- **账号与设备**：一次部署一个账号，设备 token 可吊销，用短时效配对码或手机间直接扫码入网。
- **在线状态**：由连接生命周期派生，不接受客户端自报。
- **路由**：一台设备请求另一台设备创建、配置或运行它的 bot、分块传输工作区附件与 `present` 富媒体产物，事件沿同一通道流回。
- **共享记忆**：记忆文档（`SOUL.md`、`USER.md`、`MEMORY.md`、`logs/YYYY-MM-DD.md`）只有一份权威副本，写入带来源标记与版本校验，并提供多机记忆蒸馏互斥租约（`/v1/memory/rollup-lease`）。
- **传输**：自签 CA 下的 HTTPS 与 WSS，配对时固定证书指纹。

它不跑模型、不执行工具、不解释会话内容。

## 目录

```
packages/protocol/   各端共用的线协议与 JSON 测试向量
src/server/          HTTP 与 WebSocket 服务、配置、入口
test/                服务端测试
scripts/             部署、证书与备份脚本
```

## 运行

```bash
pnpm install
SUPERPI_PORT=8443 SUPERPI_HOST=0.0.0.0 pnpm start
```

`GET /v1/health` 返回版本与协议版本。配置来自环境变量：`SUPERPI_HOST`、`SUPERPI_PORT`、`SUPERPI_DATA_DIR`、`SUPERPI_CERT`、`SUPERPI_KEY`。

生产环境用 systemd 托管并配一张自签证书，见 `scripts/` 与设计文档中的部署一节。

## 开发

```bash
pnpm typecheck
pnpm test
pnpm dev
```

## 协议

线协议在 `packages/protocol`：帧类型、错误码、记忆写入操作与 HTTP 载荷；`packages/protocol/vectors` 下的 JSON 向量供各端实现共用，`packages/protocol` 包本身是权威来源。

## 许可证

Apache-2.0，见 [LICENSE](LICENSE)。
