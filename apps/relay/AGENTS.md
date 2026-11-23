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

环境变量：`SUPERPI_HOST`、`SUPERPI_PORT`、`SUPERPI_DATA_DIR`、`SUPERPI_CERT`、`SUPERPI_KEY`。

## 关键事实

- 协议包是唯一来源：改帧、错误码、记忆操作先改 `packages/protocol` 与 `vectors/`，再改服务端与两端客户端。
- Node 24 原生运行 TypeScript（类型剥离），代码只用可擦除语法：不用 enum、namespace、参数属性。
- 在线状态由连接生命周期派生；不接受客户端自报。
- `relay.db` 持久化账号、设备、Memory v2 结构化记忆条目与实体、合并压缩的变更日志与整理租约。
- 服务端不解释会话内容，只做校验、转发与存储。

## 边界

不跑模型、不执行工具；协议改动必须同步更新客户端 Kotlin 数据类。
