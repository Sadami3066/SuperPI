# Super PI Agent plugin sample

Test fixture for the host repository's end-to-end regression (not a plugin for the marketplace): a `sample_echo` tool and a `sample.main` page that verify installing, enabling, disabling, upgrading and uninstalling external plugins.

- Plugin id: `io.github.whywood.deepdeep.pluginsample`
- Entry class: `io.github.whywood.deepdeep.pluginsample.SamplePlugin`
- Build: `./gradlew verifyPlugin`; the artifact is `build/plugin/sample.ddp`
- Signing key: `signing/dev-ed25519.pem` is for this fixture only and is committed with the repository; never use it to publish a real plugin

When the host repository runs `scripts/plugin-e2e.sh`, it pushes the `sample.ddp` built here to the device and runs `ExternalPluginE2ETest`.
