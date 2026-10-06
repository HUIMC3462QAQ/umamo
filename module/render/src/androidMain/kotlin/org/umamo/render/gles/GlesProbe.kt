package org.umamo.render.gles

import android.opengl.GLES20
import org.umamo.render.GridColors
import org.umamo.render.device.LoadAction
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RenderPassSpec
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.StoreAction
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.WorldToNdc

/**
 * The on-device GPU self-check - what stands in for the desktop's headless-GL oracle suite on Android.
 *
 * The desktop validates the renderer with `GpuDeformValidationTest` and friends, which drive a real GLFW /
 * OpenGL 3.3 context under Xvfb. Android has no equivalent: the suite lives in `jvmTest` and cannot run on
 * a device. This probe is the Android substitute - it drives a real EGL context on real hardware and
 * reports what the port got right and what it did not.
 *
 * What it covers, in order, each step reported separately so a failure names itself:
 *  1. an EGL ES 3.0 context on a pbuffer ([GlesOffscreenContext])
 *  2. the backend string, so the report records which driver actually ran
 *  3. **every** Es300 pipeline compiles and links - including the glue shader's 2D-texelFetch variant,
 *     which is the piece the GLSL port had to change
 *  4. a render target + a grid-backdrop pass + a synchronous read-back, which together exercise pass
 *     recording, attribute-less draws, framebuffer objects and the bottom-up read flip
 *  5. the pass-1 deformed-position store: create, then refresh it (the buffer -> PBO -> RG32F-texture hop)
 *     and check for a GL error. This is the port's riskiest divergence - Android's GLES binding has no
 *     int-offset `glTexSubImage2D`, so the upload passes a null client pointer and relies on the bound PBO
 *     supplying offset 0.
 *
 * Run it on the render thread with no other GL context current.
 *
 * @param Int width  The probe render target's width, in pixels.
 * @param Int height The probe render target's height, in pixels.
 */
public class GlesProbe(
	private val width: Int = 256,
	private val height: Int = 256,
) {
	/**
	 * What the probe found.
	 *
	 * @property Boolean    ok       True when every step passed.
	 * @property String     report   The human-readable transcript, one line per step.
	 * @property ByteArray? pixels   The read-back image (RGBA8888, top row first), or null when the probe
	 *   failed before reaching the read-back.
	 * @property Int        width    The read-back image's width.
	 * @property Int        height   The read-back image's height.
	 */
	public class Result(
		public val ok: Boolean,
		public val report: String,
		public val pixels: ByteArray?,
		public val width: Int,
		public val height: Int,
	)

	/** Runs the probe. Never throws: a failure is reported in [Result.report]. */
	public fun run(): Result {
		val log = StringBuilder()
		val context = GlesOffscreenContext()
		if (!context.createAndMakeCurrent()) {
			return Result(false, "EGL: FAILED - ${context.failureReason()}\n", null, 0, 0)
		}
		try {
			log.append("EGL: OK (").append(context.describeContext()).append(")\n")
			val device = GlesRenderDevice()
			log.append("backend: ").append(device.describeBackend()).append('\n')

			val pipelinesOk = probePipelines(device, log)
			val renderOk = probeRenderPass(device, log)
			val storeOk = probeDeformedPositionStore(device, log)

			val pixels = if (renderOk) lastPixels else null
			val ok = pipelinesOk && renderOk && storeOk
			log.append(if (ok) "RESULT: PASS\n" else "RESULT: FAIL\n")
			return Result(ok, log.toString(), pixels, if (renderOk) width else 0, if (renderOk) height else 0)
		} catch (failure: Throwable) {
			// A Java-side rejection (a null pointer the JNI glue refuses, say) lands here rather than as a
			// GL error, and is exactly the sort of thing this probe exists to surface.
			log.append("EXCEPTION: ").append(failure::class.simpleName).append(": ").append(failure.message).append('\n')
			log.append("RESULT: FAIL\n")
			return Result(false, log.toString(), null, 0, 0)
		} finally {
			context.destroy()
		}
	}

	/** Compiles and links one pipeline per purpose; each is a shader-compile test in its own right. */
	private fun probePipelines(device: GlesRenderDevice, log: StringBuilder): Boolean {
		var ok = true
		for (purpose in PipelinePurpose.entries) {
			val blend =
				when (purpose) {
					PipelinePurpose.GridBackdrop -> PipelineBlend.Opaque
					PipelinePurpose.Composite -> PipelineBlend.Opaque
					else -> PipelineBlend.Normal
				}
			try {
				device.createRenderPipeline(RenderPipelineSpec(purpose, blend, cullBackFaces = false))
				log.append("pipeline ").append(purpose.name).append(": OK\n")
			} catch (failure: Throwable) {
				ok = false
				log.append("pipeline ").append(purpose.name).append(": FAILED - ")
					.append(failure.message?.lineSequence()?.firstOrNull() ?: failure::class.simpleName).append('\n')
			}
		}
		try {
			device.createDeformCapturePipeline()
			log.append("pipeline DeformCapture: OK\n")
		} catch (failure: Throwable) {
			ok = false
			log.append("pipeline DeformCapture: FAILED - ").append(failure.message).append('\n')
		}
		return ok
	}

	/** Draws the grid backdrop into a sampled target and reads it back. */
	private fun probeRenderPass(device: GlesRenderDevice, log: StringBuilder): Boolean {
		val target = device.createRenderTarget(RenderTargetSpec(width, height, TextureFormat.Rgba8, sampled = true))
		val grid = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.GridBackdrop, PipelineBlend.Opaque))
		try {
			val frame = device.beginFrame()
			val pass =
				frame.beginRenderPass(
					RenderPassSpec(
						colorTarget = target,
						loadAction = LoadAction.Clear,
						viewportWidth = width,
						viewportHeight = height,
						storeAction = StoreAction.Store,
						clearRed = 0.05f,
						clearGreen = 0.05f,
						clearBlue = 0.07f,
						clearAlpha = 1f,
					),
				)
			pass.setPipeline(grid)
			// World -> NDC over a 256-unit square centred on the origin: the grid's own origin and spacing
			// then land in the middle of the target, so lines are visible in the read-back.
			pass.setCamera(WorldToNdc(2f / width, 2f / height, -1f, -1f), width.toFloat().toInt(), height)
			pass.drawGrid(
				org.umamo.render.device.GridUniforms(
					worldToNdc = WorldToNdc(2f / width, 2f / height, -1f, -1f),
					viewportWidth = width,
					viewportHeight = height,
					originX = 0f,
					originY = 0f,
					majorSpacingX = 64f,
					majorSpacingY = 64f,
					subdivisions = 4,
					lineWidthPx = 1.5f,
					colors =
						GridColors(
							backgroundRed = 0.10f,
							backgroundGreen = 0.10f,
							backgroundBlue = 0.13f,
							majorRed = 0.55f,
							majorGreen = 0.55f,
							majorBlue = 0.60f,
							minorRed = 0.22f,
							minorGreen = 0.22f,
							minorBlue = 0.26f,
						),
				),
			)
			pass.end()
			frame.endFrame()
			val glError = GLES20.glGetError()
			if (glError != GLES20.GL_NO_ERROR) {
				log.append("grid pass: GL error 0x").append(Integer.toHexString(glError)).append('\n')
				return false
			}
			val image = device.readPixels(target)
			lastPixels = image.rgba
			val distinct = HashSet<Int>()
			var nonClear = 0
			var index = 0
			while (index + 3 < image.rgba.size) {
				val r = image.rgba[index].toInt() and 0xFF
				val g = image.rgba[index + 1].toInt() and 0xFF
				val b = image.rgba[index + 2].toInt() and 0xFF
				distinct.add((r shl 16) or (g shl 8) or b)
				if (r > 20 || g > 20 || b > 20) nonClear++
				index += 4
			}
			log.append("grid pass: OK, read back ").append(image.width).append('x').append(image.height)
				.append(", ").append(distinct.size).append(" distinct colours, ").append(nonClear)
				.append(" lit pixels\n")
			// A grid over a dark backdrop must produce both the backdrop and at least one line colour; a
			// uniform image means the pass drew nothing (or the read-back landed somewhere else).
			if (distinct.size < 2 || nonClear == 0) {
				log.append("grid pass: FAILED - the read-back is uniform, so nothing was drawn\n")
				return false
			}
			return true
		} finally {
			device.destroyRenderTarget(target)
		}
	}

	/**
	 * Exercises the pass-1 store: allocate, refresh (buffer -> PBO -> RG32F texture), and check for a GL
	 * error. This is where the port diverges hardest from desktop, so it is worth a probe of its own.
	 */
	private fun probeDeformedPositionStore(device: GlesRenderDevice, log: StringBuilder): Boolean {
		val capacity = 4 * STORE_PROBE_VERTICES
		val store = device.createDeformedPositionStore(capacity) as GlesDeformedPositionStore
		var glError = GLES20.glGetError()
		if (glError != GLES20.GL_NO_ERROR) {
			log.append("store allocate: GL error 0x").append(Integer.toHexString(glError)).append('\n')
			return false
		}
		log.append("store allocate: OK (").append(store.width).append('x').append(store.height)
			.append(" RG32F texture for ").append(capacity).append(" vertices)\n")
		try {
			device.syncDeformedPositionStore(store)
			glError = GLES20.glGetError()
			if (glError != GLES20.GL_NO_ERROR) {
				log.append("store refresh: GL error 0x").append(Integer.toHexString(glError))
					.append(" - the buffer -> PBO -> texture hop was rejected\n")
				return false
			}
			log.append("store refresh: OK (buffer -> PBO -> texture)\n")
			return true
		} catch (failure: Throwable) {
			log.append("store refresh: EXCEPTION ").append(failure::class.simpleName).append(": ")
				.append(failure.message).append('\n')
			return false
		}
	}

	private var lastPixels: ByteArray? = null

	private companion object {
		/** Four vertices' worth of glue positions - enough to allocate a real store without a model. */
		const val STORE_PROBE_VERTICES = 64
	}
}