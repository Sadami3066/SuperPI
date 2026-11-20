# SuperPI Desktop · 项目说明

Electron 桌面壳 + 官方 pi（RPC 子进程）。界面在 `apps/desktop/src/renderer`，主进程在 `src/main`，pi 接入在 `src/main/pi`。

## 命令

```bash
pnpm install
pnpm typecheck
pnpm test
pnpm build          # 主进程与 preload 用 esbuild 打包，界面用 vite 构建
pnpm start          # 启动 Electron
```

## 关键事实

- pi 以子进程运行，`cwd` 是 bot 的工作目录；恢复会话用 `--session`。
- RPC 记录严格 JSONL，只用 `\n` 切分（不能按 Unicode 行分隔符切）；响应按 `id` 关联，不按顺序。
- `agent_settled` 才是真空闲；`agent_end` 之后仍可能有重试、压缩、steer。
- 关闭 pi 先关 stdin 等有序退出，超时才 SIGTERM，最后 SIGKILL。
- 主进程是 pi 连接的唯一持有者；渲染进程只经 preload 的通道通信。
- SuperPI 自己的状态在 `~/.superpi`（bot 清单 `bots.json`、默认工作目录 `workspace/`）；pi 的凭据与会话在它自己的配置目录。
- 本机 Electron 有两个环境坑：`pnpm install` 的 postinstall 由 `scripts/ensure-electron.mjs` 兜底解包（避免 extract-zip 在 Node 24 上静默失败）；启动若报 SUID sandbox，跑一次 `scripts/fix-sandbox.sh`。
- 无头验收：`xvfb-run ... pnpm start` 配合 `scripts/dev-screenshot.mjs` 截图。

## 边界

渲染进程不直接访问网络与文件；主进程不做界面逻辑。新增 IPC 时同步更新 preload 与 `window.superpi` 类型。
