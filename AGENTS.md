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
- SuperPI 自己的状态在 `~/.superpi`（bot 清单 `bots.json`、窗口位置、每 bot 上次会话与远程会话 id `desktop.json`、默认工作目录 `workspace/`）；`bots.json` 每条只存 `id`/`name`/`cwd`，旧文件的其它字段读时忽略，默认 bot 固定为 `pi`（不可删，列表为空时自动补回）。pi 的凭据与会话在它自己的配置目录。
- 会话：pi 的会话文件位于 `~/.pi/agent/sessions/--<cwd 编码>--/`（`PI_CODING_AGENT_DIR` / `PI_CODING_AGENT_SESSION_DIR` / `sessionDir` 设置会改位置，`src/main/pi/sessions.ts` 按同一优先级解析）；侧栏按 bot cwd 列出会话，切换走 RPC `switch_session`，新会话走 `new_session`，历史消息用 `get_messages` 还原；每个 bot 上次会话记在 `desktop.json`，启动时用 `--session` 恢复。
- 模型：顶部选择器读 `get_available_models`/`get_state`，切换走 `set_model`，思考档位读 `get_available_thinking_levels` 走 `set_thinking_level`；模型是会话级选择，由 pi 记在会话文件里。
- 常驻：关窗只是隐藏，进程留在托盘（左键切换窗口，菜单显示/退出），窗口位置无效时回主屏居中；单实例锁保证第二次启动只唤起已有窗口；退出时按上面顺序关掉所有 pi，托盘销毁。
- 本机 Electron 有两个环境坑：`pnpm install` 的 postinstall 由 `scripts/ensure-electron.mjs` 兜底解包（避免 extract-zip 在 Node 24 上静默失败）；启动若报 SUID sandbox，跑一次 `scripts/fix-sandbox.sh`。
- 无头验收：`xvfb-run ... pnpm start` 配合 `scripts/dev-screenshot.mjs` 截图。
- 中继接入在 `src/main/relay/`：配对链接解析、凭据（`~/.superpi/relay.json`，0600）、CA 指纹比对、HTTP/WSS 客户端、自动重连；IPC 表面是 `relay:status` / `relay:pair` / `relay:logout`，状态经 `relay:state` 推给界面。
- 中继客户端集成测试会真起 `../SuperPI-Remote` 的服务（含 TLS 证书生成），跨仓路径不存在时自动跳过。
- 远程调用：`RelayService.startRemote/promptRemote/abortRemote` 经 `route` 帧驱动另一台设备上的 bot；事件以 `remote:event` 推到界面，界面用同一套 Entry 渲染。远端离线时入口置灰、启动会话直接报错。
- 被远程调用：`relay/remote-server.ts` 处理入站 `route`（`bot.prompt`/`steer`/`follow_up`/`abort`/`state`/`resume`）。每个 bot 在第一次远程 prompt 时懒起一个**专用的** pi 子进程（同一 cwd / 扩展 / 模型配置，但与会话窗口的本地 pi 完全隔离），之后复用；空闲 10 分钟（`RemoteAgentServer` 的 `idleTimeoutMs`）停掉，退出/登出时全部关掉。每个 prompt 先回 `route_ok {sessionId}`，随后用同一请求 id 流式回 pi 事件（agent_start / message_update / tool_call / tool_result / agent_end / agent_settled / error），`agent_settled` 是这条 route 的终点；中继只接受协议里的 BotEvent 形状。
- 记忆缓存：`src/main/relay/memory-client.ts` 把中继快照镜像到 `~/.superpi/cache/memory/`（`snapshot.json` 存版本与内容），服务在连上中继和收到 `memory_changed` 时刷新；状态经 `memory:state` 推给界面。
- 记忆注入：`extensions/superpi-memory.mjs` 由 pi 以 `-e` 加载，在 `before_agent_start` 写 `systemPromptOptions.sections.memory`，并注册 `memory_read`/`memory_search`/`memory_write`；中继地址与 token 由主进程经环境变量传入，扩展不读凭据文件。

## 边界

渲染进程不直接访问网络与文件；主进程不做界面逻辑。新增 IPC 时同步更新 preload 与 `window.superpi` 类型。
