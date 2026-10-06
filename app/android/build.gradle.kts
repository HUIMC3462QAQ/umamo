import java.security.MessageDigest

// :android (app/android) — Android entrypoint (Activity + Compose + GLSurfaceView viewport).
// A plain Android application module (not KMP): it consumes the shared KMP libraries.
// :android — Android 起動点（Activity ＋ Compose ＋ GLSurfaceView ビューポート）。

plugins {
	// AGP 9+ has built-in Kotlin support, so the org.jetbrains.kotlin.android plugin is gone —
	// applying it now errors. AGP compiles Kotlin itself; the Compose compiler plugin hooks in.
	alias(libs.plugins.androidApplication)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// Resolve the application version from ProjectInfo.kt (see gradle/project-version.gradle.kts) so
// versionName never drifts from what the About dialog shows.
apply(from = rootProject.file("gradle/project-version.gradle.kts"))
val umamoVersion = extra["umamoVersion"] as String

android {
	namespace = "org.umamo.editor.android"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		// applicationId is the permanent Play identity; it intentionally differs from `namespace`
		// so org.umamo stays a clean umbrella for a future viewer app.
		applicationId = "org.umamo.editor"
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()
		// versionCode is Play's monotonic release counter — bumped by hand on release; it cannot be
		// derived from a semver string.
		versionCode = 1
		versionName = umamoVersion
	}

	buildTypes {
		release {
			isMinifyEnabled = false
		}
	}

	// Keep AGP's Java compilation in lockstep with Kotlin's JDK 21 target (otherwise the
	// "inconsistent JVM target" error bites the moment any Java source appears).
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}
}

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation(project(":ui"))
	implementation(project(":runtime"))
	// Editing core (EditorSession / Selection model): the Android host creates a per-document session
	// the same way the desktop does; declared directly for parity with :runtime / :render.
	implementation(project(":edit"))
	implementation(project(":render"))
	// The off-screen viewport stack; this host supplies the GLES device + EGL context.
	implementation(project(":viewport"))
	// androidAppStorage + the per-OS config/data dirs the settings live in; also pulls FileKit
	// (api-exposed by :storage), whose Android pickers MainActivity initialises with FileKit.init.
	implementation(project(":storage"))
	// The Settings type returned by LocalSettings: :ui depends on :settings only via implementation, so
	// MainActivity needs it on its own classpath to read settings.getString / observe settings.changes.
	implementation(project(":settings"))

	// setContent { } lives in androidx.activity.compose.
	implementation(libs.androidx.activity.compose)
	// Direct Compose Multiplatform coordinates via the version catalog — the old `compose.runtime`
	// plugin aliases were deprecated in CMP 1.10/1.11. The artifacts still redirect to
	// androidx.compose on this Android target (Gradle metadata), so resolution is unchanged; see the
	// catalog note on the `compose-*` entries and the matching :ui commonMain block.
	implementation(libs.compose.runtime)
	implementation(libs.compose.foundation)
	implementation(libs.compose.ui)
}

// The app ships its OWN copy of :ui's compose resources, at
// src/main/assets/composeResources/<resource package>/. It has to: Compose Multiplatform's resource
// pipeline never delivered them into this app's assets, and on Android the resource reader resolves them
// through ASSETS - so without the copy the app died before its first frame with
// `MissingResourceException: composeResources/org.umamo.ui.resources/files/defaultSettings.json`, and
// every string, font and icon was missing too.
//
// A checked-in copy can drift from its source, so this guard fails the build when it does. It is wired in
// ahead of preBuild rather than left as an optional task, because a stale copy is exactly the bug it
// exists to prevent and would otherwise ship silently.
val composeResourcesSourceDirectory = rootProject.layout.projectDirectory.dir("module/ui/src/commonMain/composeResources")
val shippedComposeAssetsDirectory = layout.projectDirectory.dir("src/main/assets/composeResources/org.umamo.ui.resources")

val verifyComposeAssets =
	tasks.register("verifyComposeAssets") {
		description = "Fails when the app's checked-in compose resources drift from :ui's."
		val sourceDirectory = composeResourcesSourceDirectory.asFile
		val shippedDirectory = shippedComposeAssetsDirectory.asFile
		val creditsFile = rootProject.file("CREDITS.md")
		doLast {
			fun digestOf(file: File): String =
				MessageDigest.getInstance("SHA-256")
					.digest(file.readBytes())
					.joinToString("") { byte: Byte -> "%02x".format(byte) }

			val expected = LinkedHashMap<String, String>()
			sourceDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
				expected[file.relativeTo(sourceDirectory).invariantSeparatorsPath] = digestOf(file)
			}
			// :ui's own build sync folds CREDITS.md in under files/, so the shipped copy carries it too.
			expected["files/CREDITS.md"] = digestOf(creditsFile)

			val problems = mutableListOf<String>()
			expected.forEach { (path, digest) ->
				val shipped = File(shippedDirectory, path)
				when {
					!shipped.isFile -> problems += "missing from the app: $path"
					digestOf(shipped) != digest -> problems += "differs from :ui: $path"
				}
			}
			shippedDirectory.walkTopDown().filter { it.isFile }.forEach { file ->
				val path = file.relativeTo(shippedDirectory).invariantSeparatorsPath
				if (path !in expected) problems += "not present in :ui: $path"
			}
			if (problems.isNotEmpty()) {
				throw GradleException(
					"compose resources have drifted:\n  " + problems.joinToString("\n  ") +
						"\nRe-copy module/ui/src/commonMain/composeResources (plus CREDITS.md under files/) into " +
						"app/android/src/main/assets/composeResources/org.umamo.ui.resources/.",
				)
			}
			logger.lifecycle("verifyComposeAssets: ${expected.size} files match :ui")
		}
	}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verifyComposeAssets) }