// :viewport — the off-screen viewport stack: a render engine on its own thread, the area registry that
// maps viewport areas to cameras, the CPU picker, and the PuppetViewportService facade the editor shell
// consumes. Shared by the desktop and Android hosts: each supplies its own RenderDevice and
// OffscreenGlContext, which is the whole reason this module exists rather than living in app/desktop.
//
// The sources sit in jvmAndroidMain (not commonMain) because they are JVM-flavoured shared code - java.util
// collections, java.io.File - which is exactly what the `umamo.kmp-jvmandroid` convention plugin's group is
// for. The package is still org.umamo.editor.desktop.viewport: renaming it would touch the desktop host's
// imports for no behavioural gain, and this fork would rather keep that diff empty.
// :viewport — オフスクリーン・ビューポートの中核。デスクトップと Android で共有する。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	id("umamo.kmp-jvmandroid")
}

kotlin {
	jvmToolchain(21)

	jvm()

	android {
		namespace = "org.umamo.viewport"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		// The `umamo.kmp-jvmandroid` convention plugin creates this group and wires androidMain onto it;
		// it is not a well-known source-set name, so it has no generated accessor and is looked up here.
		val jvmAndroidMain = getByName("jvmAndroidMain")

		jvmAndroidMain.dependencies {
			// `api` on :ui because PuppetViewportService (and LiveParams, ViewportCamera, ContentBounds)
			// live there and this module's public surface is built from them - the hosts see them
			// transitively, exactly as they did when these classes sat in app/desktop.
			api(project(":ui"))
			// :render supplies RenderDevice (the injected backend seam) and the PuppetRenderer;
			// :runtime supplies PuppetModel, which the engine and the picker both walk.
			api(project(":render"))
			api(project(":runtime"))
			// `api` because GridConfig sits in a public property of the engine (its grid backdrop
			// settings), so a consumer compiles against the type. :ui has :edit only as
			// `implementation`, so it does not arrive transitively.
			api(project(":edit"))
			// `api` for the same reason: CompletableDeferred (a coroutine type) is part of a public
			// nested type of the engine's image-capture job.
			api(libs.kotlinxCoroutinesCore)
			// Compose types (ImageBitmap) appear in PuppetViewportService's surface, which this module
			// implements; :ui does not re-export them.
			api(libs.compose.ui)
			api(libs.compose.runtime)
			// UmamoLog is used inside function bodies only, so it stays off the compile classpath of
			// anything that consumes this module.
			implementation(project(":storage"))
		}
	}
}