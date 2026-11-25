# SuperPI Remote · 项目说明

自托管中继：账号与设备、在线状态、消息路由、共享记忆。线协议在 `packages/protocol`，服务端在 `src/server`。

## 命令

```bash
pnpm install
pnpm typecheck
pnpm test
pnpm dev      # 监听重启
pnpm start    # 生产启动
```

环境变量：`SUPERPI_HOST`、`SUPERPI_PORT`、`SUPERPI_DATA_DIR`、`SUPERPI_CERT`、`SUPERPI_KEY`、`SUPERPI_CA`、`SUPERPI_PUBLIC_ORIGIN`、`SUPERPI_LAN_ORIGIN`。

## 关键事实

- 协议包是唯一来源：改帧、错误码、记忆操作先改 `packages/protocol` 与 `vectors/`，再改服务端与两端客户端。
- Node 24 原生运行 TypeScript（类型剥离），代码只用可擦除语法：不用 enum、namespace、参数属性。
- 在线状态由连接生命周期派生；不接受客户端自报。
- `relay.db` 持久化账号、设备、配对码、Memory v3 结构化记忆（blocks/entities/events/relations/proposals）、合并压缩的变更日志与整理租约。
- 限额在 `src/server/realtime.ts` 与 `src/server/http.ts`：WS 帧 8 MiB、单次路由结果 4 MiB（`payload_too_large`，不断连）、未知 `route_error.code` 归一为 `internal`、HTTP JSON 64 KiB（Memory v3 4 MiB）、读取超时 30 秒（注册 15 秒）。
- 服务端不解释会话内容，只做校验、转发与存储。

## 边界

不跑模型、不执行工具；协议改动必须同步更新客户端 Kotlin 数据类。
