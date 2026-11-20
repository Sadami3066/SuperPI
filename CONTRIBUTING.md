# Contributing to SuperPI-Remote

Thank you for contributing to SuperPI-Remote, the self-hosted relay, presence, and shared memory server for SuperPI devices.

## Development Workflow

1. **Requirements**: Node.js 24+ and `pnpm`.
2. **Install & Verify**:
   ```bash
   pnpm install
   pnpm typecheck
   pnpm test
   ```
3. **Architecture & Boundary Rules**:
   - The relay server routes opaque JSONL frames, tracks live WebSocket presence, and manages the single authoritative Markdown memory copy with optimistic concurrency (`expectedVersion`).
   - It must never execute model calls, run tools, or inspect chat payloads.
   - Keep protocol changes synchronized with `packages/protocol` and its JSON test vectors.
4. **Code Style**: TypeScript strict mode, no comments in source files, and comprehensive unit/integration tests for every protocol or storage change.
5. **License**: Contributions are licensed under the [Apache-2.0 License](LICENSE).
