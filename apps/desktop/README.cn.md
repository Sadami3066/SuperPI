# SuperPI Desktop

SuperPI 的桌面端，构建在 [pi](https://pi.dev) coding agent 之上。

手机上的 SuperPI 是个人 agent 应用；这里是同一个助手在电脑上的形态：把未经改动的 pi 当作子进程，通过它的 RPC 模式驱动，bot 就是一个工作目录，会话、工具输出与上下文都留在这台机器上。

## 它做什么

- **bot 就是目录**：一个 bot 对应一个工作目录，它的指令是该目录里的 `AGENTS.md`；默认 bot 叫 PI。
- **运行 pi，而不是重写它**：内核是官方 TypeScript pi，经 `pi --mode rpc` 以 JSONL 驱动，上游修复直接到手。
- **本地优先**：会话、凭据与模型配置来自 pi 自身；SuperPI 只在 `~/.superpi` 保存自己的状态。
- **桌面原生表面**：窗口、bot 抽屉、折进对话里的工具卡、键盘优先的输入区。

设备之间可以经由 [`apps/relay`](../relay) 的中继互通：同一批 bot 出现在抽屉里，任一设备都能被要求运行自己的 bot。

## 目录

```
apps/desktop/src/main/       Electron 主进程：窗口、IPC、bot 清单、pi RPC 客户端
apps/desktop/src/preload/    contextBridge 接口
apps/desktop/src/renderer/   React 界面
apps/desktop/test/           主进程测试（假 pi 子进程）
```

## 要求

- Node.js 22 以上
- `PATH` 里有 `pi`（`npm install -g @earendil-works/pi-coding-agent`）
- 首发 Linux；壳本身跨平台，但只验证 Linux

## 运行

```bash
pnpm install
pnpm build
pnpm start
```

用 `SUPERPI_PI_BIN` 指定别的 pi 可执行文件。

Linux 上若启动时报 SUID sandbox 错误，安装后运行一次 `bash scripts/fix-sandbox.sh`，把 Electron 的 sandbox helper 设为 root 所有的 setuid 文件；也可以用 `pnpm start:nosandbox` 临时绕过。

## 开发

```bash
pnpm typecheck
pnpm test
pnpm build
```

## 许可证

[Apache-2.0](../../LICENSE)。
