# Contributing to SuperPI-Plugins

Thank you for contributing to the SuperPI external plugin template and developer documentation.

## Guidelines

1. **Template & Sample Integrity**: Any change to `template/` or `sample/` must compile against the published `io.github.sadami3066.superpi:plugin-api` and pass `./gradlew verifyPlugin`.
2. **No Private Keys**: Never commit real signing keys (`keys/*.pem`). `sample/signing/dev-ed25519.pem` is a test-only fixture key for automated host E2E tests and must never be used to sign marketplace plugins.
3. **Host Dependencies**: All host-provided libraries (`plugin-api`, `design`, Compose, Coroutines, Serialization) must remain `compileOnly`.
4. **License**: Contributions are licensed under the [Apache-2.0 License](LICENSE).
