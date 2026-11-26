plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val includeAndroid: Boolean = findProject(":apps:android:app") != null

val verifyModuleBoundaries = tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Checks app/host -> core/android module dependency boundaries."
}

val verifyPackageBoundaries = tasks.register("verifyPackageBoundaries") {
    group = "verification"
    description = "Checks package-level import rules that module boundaries cannot express."
    val sourceRoots = buildMap {
        put("runtime-api", layout.projectDirectory.dir("core/runtime-api/src/main/kotlin"))
        put("runtime", layout.projectDirectory.dir("core/runtime/src/jvmCommonMain/kotlin"))
        put("runtime-jvm", layout.projectDirectory.dir("core/runtime/src/jvmMain/kotlin"))
        put("relay", layout.projectDirectory.dir("core/relay/src/jvmCommonMain/kotlin"))
        put("relay-jvm", layout.projectDirectory.dir("core/relay/src/jvmMain/kotlin"))
        put("host", layout.projectDirectory.dir("apps/host/src/main/kotlin"))
        if (includeAndroid) {
            put("app", layout.projectDirectory.dir("apps/android/app/src/main/kotlin"))
            put("runtime-android", layout.projectDirectory.dir("core/runtime/src/androidMain/kotlin"))
            put("relay-android", layout.projectDirectory.dir("core/relay/src/androidMain/kotlin"))
            put("shell", layout.projectDirectory.dir("apps/android/shell/src/main/kotlin"))
            put("design", layout.projectDirectory.dir("apps/android/design/src/main/kotlin"))
        }
    }
    val allowlistFile = layout.projectDirectory.file("gradle/package-boundaries.allow")
    val strictBoundaries = providers.gradleProperty("superpi.strictBoundaries").orNull == "true"
    val checkStaleAllowlist = includeAndroid
    inputs.files(sourceRoots.values)
    inputs.file(allowlistFile)
    inputs.property("strictBoundaries", strictBoundaries)
    inputs.property("checkStaleAllowlist", checkStaleAllowlist)
    doLast {
        val base = "io.github.whywood.superpi"
        val allowlist = allowlistFile.asFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        val observed = mutableSetOf<String>()
        val introduced = mutableListOf<String>()
        fun check(coupling: String, subject: String) {
            if (observed.add(coupling)) {
                if (coupling !in allowlist) introduced += "$subject ($coupling)"
            }
        }
        for ((module, rootRef) in sourceRoots) {
            val root = rootRef.asFile
            if (!root.isDirectory) continue
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val relative = file.relativeTo(root).path.replace('\\', '/')
                val packageName = relative.substringBeforeLast('/', "").replace('/', '.')
                val compositionLayer = packageName == "$base.ui.navigation" ||
                    packageName.startsWith("$base.ui.navigation.") ||
                    packageName == "$base.ui.shell" ||
                    packageName.startsWith("$base.ui.shell.")
                file.readLines().forEach { raw ->
                    val line = raw.trim()
                    if (!line.startsWith("import ")) return@forEach
                    val imported = line.removePrefix("import ").removePrefix("import\t").substringBefore(" as ").trim()
                    if (!imported.startsWith(base)) return@forEach
                    if (module != "app" && (imported == "$base.AppGraph" || imported == "$base.SuperPIApplication" || imported.startsWith("$base.ui."))) {
                        check("$module/$relative|$imported", "module $module")
                    }
                    if (module == "app" && packageName.startsWith("$base.ui.")) {
                        if (imported == "$base.AppGraph" || imported == "$base.SuperPIApplication") {
                            check("ui/$relative|$imported", "shell access from ui")
                        }
                        val parts = imported.split('.')
                        if (parts.size > 6 && parts[4] == "ui" && !compositionLayer) {
                            val mine = packageName.removePrefix("$base.ui.").substringBefore('.')
                            val target = parts[5]
                            if (target != mine && target != "common" && target != "navigation") {
                                check("ui/$relative|$imported", "cross-feature import")
                            }
                        }
                        if (imported.startsWith("$base.assistant.")) {
                            check("ui/$relative|$imported", "ui shell access to assistant")
                        }
                        if ((packageName.startsWith("$base.assistant") || packageName.startsWith("$base.floating")) &&
                            imported.startsWith("$base.ui.chat.")
                        ) {
                            check("app/$relative|$imported", "chat access from assistant surface")
                        }
                    }
                }
            }
        }
        logger.lifecycle("Package boundaries: composition layer (ui.navigation/ui.shell) may import features; ui may never import AppGraph/SuperPIApplication or assistant; assistant/floating may not import ui.chat")
        if (checkStaleAllowlist) {
            val stale = allowlist - observed
            if (stale.isNotEmpty()) {
                val message = "Package boundary allowlist has ${stale.size} stale entries (remove them):\n" +
                    stale.sorted().joinToString("\n")
                check(!strictBoundaries) { message }
                logger.lifecycle(message)
            }
        }
        check(introduced.isEmpty()) {
            "New package boundary violations (fix the import, or add to gradle/package-boundaries.allow only if intentional):\n" +
                introduced.joinToString("\n")
        }
        logger.lifecycle("Package boundaries verified (${observed.size} tolerated couplings)")
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        environment("LC_ALL", "C")
        environment("TZ", "UTC")
    }
}

val verifyDocFacts = tasks.register("verifyDocFacts") {
    val noticesRoot = layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
    val noticesPackaged = layout.projectDirectory.file("apps/android/app/src/main/assets/licenses/THIRD_PARTY_NOTICES.md")
    val versionCatalog = layout.projectDirectory.file("gradle/libs.versions.toml")
    inputs.files(noticesRoot, noticesPackaged, versionCatalog)
    doLast {
        check(noticesRoot.asFile.readText() == noticesPackaged.asFile.readText()) {
            "THIRD_PARTY_NOTICES.md 与 apps/android/app/src/main/assets/licenses 下的打包副本不一致：改根文件后同步复制"
        }
        val sdkVersion = Regex("pi = \"v?([^\"]+)\"").find(versionCatalog.asFile.readText())
            ?.groupValues?.get(1) ?: error("cannot read pi version from gradle/libs.versions.toml")
        check(noticesRoot.asFile.readText().contains("pi2kotlin SDK $sdkVersion")) {
            "THIRD_PARTY_NOTICES.md 的 SDK 版本与 gradle/libs.versions.toml 的 pi($sdkVersion) 不一致"
        }
        logger.lifecycle("Doc facts verified (SDK $sdkVersion)")
    }
}

gradle.projectsEvaluated {
    val allowed = mapOf(
        ":core:runtime-api" to emptySet(),
        ":core:runtime" to setOf(":core:runtime-api"),
        ":core:relay" to setOf(":core:runtime", ":core:runtime-api"),
        ":apps:host" to setOf(":core:relay", ":core:runtime", ":core:runtime-api"),
        ":apps:android:app" to setOf(
            ":core:relay",
            ":core:runtime",
            ":core:runtime-api",
            ":apps:android:shell",
            ":apps:android:design",
            ":apps:android:plugin-api",
        ),
        ":apps:android:shell" to emptySet(),
        ":apps:android:design" to emptySet(),
        ":apps:android:plugin-api" to setOf(":apps:android:design"),
    )
    val leafModules = subprojects.filter { it.childProjects.isEmpty() }
    val undeclared = leafModules.map { it.path }.filterNot { it in allowed }
    check(undeclared.isEmpty()) { "Modules missing from the boundary table: ${undeclared.joinToString()}" }
    val violations = leafModules.flatMap { module ->
        val targets = allowed.getValue(module.path)
        module.configurations.flatMap { configuration ->
            configuration.dependencies.withType<org.gradle.api.artifacts.ProjectDependency>().mapNotNull { dependency ->
                val target = dependency.path
                when {
                    target == module.path -> null
                    target in targets -> null
                    else -> "${module.path} -> $target"
                }
            }
        }
    }.distinct()
    val moduleCount = leafModules.size
    verifyModuleBoundaries.configure {
        inputs.property("violations", violations)
        inputs.property("moduleCount", moduleCount)
        doLast {
            check(violations.isEmpty()) { "Invalid module dependencies: ${violations.joinToString()}" }
            logger.lifecycle("Module boundaries verified across $moduleCount modules")
        }
    }

    val unitTestTasks = subprojects.flatMap { sub ->
        listOfNotNull(
            sub.tasks.findByName("jvmTest"),
            sub.tasks.findByName("testAndroidHostTest"),
            sub.tasks.findByName("testDebugUnitTest"),
            sub.tasks.findByName("test"),
        )
    }.distinct()
    val lintTasks = subprojects.mapNotNull { it.tasks.findByName("lintDebug") }
    tasks.named("check") {
        dependsOn(verifyModuleBoundaries)
        dependsOn(verifyPackageBoundaries)
        if (includeAndroid) {
            dependsOn(verifyDocFacts)
        }
        dependsOn(unitTestTasks)
        dependsOn(lintTasks)
    }
    subprojects {
        tasks.matching { it.name == "check" }.configureEach {
            dependsOn(verifyModuleBoundaries)
            dependsOn(verifyPackageBoundaries)
        }
    }
}
