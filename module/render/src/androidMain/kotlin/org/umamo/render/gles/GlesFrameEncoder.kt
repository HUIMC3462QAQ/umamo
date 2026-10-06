package org.umamo.render.gles

import android.opengl.GLES20
import android.opengl.GLES30
import org.umamo.render.device.AxisLineUniforms
import org.umamo.render.device.CompositeUniforms
import org.umamo.render.device.DeformCapturePassEncoder
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FragmentUniforms
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.GridUniforms
import org.umamo.render.device.LoadAction
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPassSpec
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureWrap
import org.umamo.render.device.WorldToNdc
import org.umamo.render.glsl.MAX_BLEND_CORNERS
import org.umamo.render.glsl.MAX_CORNERS
import org.umamo.render.glsl.UNIT_ATLAS
import org.umamo.render.glsl.UNIT_CP
import org.umamo.render.glsl.UNIT_DELTA
import org.umamo.render.glsl.UNIT_DEST
import org.umamo.render.glsl.UNIT_LAYER
import org.umamo.render.glsl.UNIT_MASK
import org.umamo.render.glsl.UNIT_POSITION
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The GLES implementation of one frame's recorded work - the Android twin of jvmMain's `GlFrameEncoder`.
 *
 * GLES has no command buffer either, so "recording" is really "issuing immediately": each call runs
 * against the current context as it arrives. The encoder shape still earns its keep - it names the target
 * every pass writes and fixes each pipeline's blend up front - and it is what a Metal backend, which does
 * have command buffers, would map onto directly.
 *
 * The one place this diverges from desktop is where the glue program reads pass 1's output: desktop binds
 * a texture BUFFER (GLES 3.2), this binds the store's 2D texture. See [GlesDeformedPositionStore].
 *
 * @param GlesRenderDevice device   The device that created this encoder; the store refresh in [barrier]
 *   needs it.
 * @param Int              emptyVao A bound VAO for the attribute-less draws (grid, axis, atlas page);
 *   GLES 3.0 requires one even when the shader synthesises positions from gl_VertexID.
 */
internal class GlesFrameEncoder(
	private val device: GlesRenderDevice,
	private val emptyVao: Int,
) : FrameEncoder {
	override fun beginRenderPass(spec: RenderPassSpec): RenderPassEncoder {
		val target = spec.colorTarget as GlesRenderTarget
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
		GLES20.glViewport(0, 0, spec.viewportWidth, spec.viewportHeight)
		val scissor = spec.scissor
		if (scissor != null) {
			// The spec's rect is top-left-origin (the read-back convention); GLES scissors bottom-up.
			GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
			GLES20.glScissor(scissor.x, spec.viewportHeight - scissor.y - scissor.height, scissor.width, scissor.height)
		} else {
			GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
		}
		if (spec.loadAction == LoadAction.Clear) {
			// With a scissor set, the clear is confined to the rect too - that is the point: a
			// bounds-scissored composite layer never pays a full-viewport clear.
			GLES20.glClearColor(spec.clearRed, spec.clearGreen, spec.clearBlue, spec.clearAlpha)
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
		}
		// Load preserves the target's contents (a bound FBO already holds them); DontCare needs no work on
		// GL - the pass overwrites every pixel, which a tile-based backend would exploit but GL cannot.
		return GlesRenderPassEncoder(emptyVao)
	}

	override fun beginDeformCapturePass(pipeline: DeformCapturePipeline, store: DeformedPositionStore): DeformCapturePassEncoder {
		val glesPipeline = pipeline as GlesDeformCapturePipeline
		GLES20.glUseProgram(glesPipeline.program)
		GLES20.glUniform1i(glesPipeline.locations.deltaTex, UNIT_DELTA)
		GLES20.glUniform1i(glesPipeline.locations.cpTex, UNIT_CP)
		// Discard the rasterizer: the capture writes positions via transform feedback and draws nothing.
		GLES20.glEnable(GLES30.GL_RASTERIZER_DISCARD)
		return GlesDeformCapturePassEncoder(glesPipeline, store as GlesDeformedPositionStore)
	}

	override fun barrier(store: DeformedPositionStore) {
		// The declared write→read dependency on the position store.
		//
		// Desktop issues a bare glFinish here: its store IS the feedback buffer seen through a texture-buffer
		// view, and the WSL d3d12/Mesa stack does not order transform-feedback writes before a later read of
		// that view (glMemoryBarrier does not cover feedback writes - they are coherent-pipeline writes).
		//
		// This port has one more hop to order, because a texture cannot be a feedback target: the positions
		// are copied out of the buffer into a 2D texture. The finish goes FIRST so the feedback writes have
		// landed before the copy reads them; the copy's own result is then ordered against the draws that
		// sample it by ordinary GL command order within the context.
		//
		// Called only on pose-change frames, so a static pose pays nothing.
		GLES20.glFinish()
		device.syncDeformedPositionStore(store as GlesDeformedPositionStore)
	}

	override fun endFrame() {
		// Nothing to submit on GLES: the calls already ran. A command-buffer backend would commit here.
	}
}

/** Records draws into one GLES render pass. */
internal class GlesRenderPassEncoder(private val emptyVao: Int) : RenderPassEncoder {
	private var pipeline: GlesRenderPipeline? = null
	private val current: GlesRenderPipeline get() = pipeline ?: error("setPipeline before drawing")

	// Per-program state is re-established only when the program actually switches. A uniform keeps its value
	// on the program object across binds, and the renderer passes the same camera / glue state for a whole
	// pass, so re-sending them per draw is wasted work: a run of same-blend drawables would otherwise churn
	// glUseProgram + blend + samplers + camera every draw.
	private var cameraApplied = false
	private var glueStateApplied = false

	// Scratch for the corner uniform arrays, reused across draws so the per-draw marshalling never allocates.
	private val cornerCellScratch: IntBuffer = newIntBuffer(MAX_CORNERS)
	private val cornerWeightScratch: FloatBuffer = newFloatBuffer(MAX_CORNERS)

	override fun setPipeline(pipeline: RenderPipeline) {
		val glesPipeline = pipeline as GlesRenderPipeline
		if (glesPipeline === this.pipeline) {
			return // already bound: program, blend, and samplers are all still in effect
		}
		this.pipeline = glesPipeline
		cameraApplied = false
		glueStateApplied = false
		GLES20.glUseProgram(glesPipeline.program)
		applyBlend(glesPipeline.blend)
		applyCull(glesPipeline.cullBackFaces)
		// Sampler → texture unit is constant per program; -1 for a sampler the program lacks is a no-op.
		val locations = glesPipeline.locations
		GLES20.glUniform1i(locations.atlas, UNIT_ATLAS)
		GLES20.glUniform1i(locations.maskTexture, UNIT_MASK)
		GLES20.glUniform1i(locations.deltaTex, UNIT_DELTA)
		GLES20.glUniform1i(locations.cpTex, UNIT_CP)
		GLES20.glUniform1i(locations.positionBuffer, UNIT_POSITION)
		GLES20.glUniform1i(locations.layerTexture, UNIT_LAYER)
		GLES20.glUniform1i(locations.destTexture, UNIT_DEST)
	}

	override fun setCamera(worldToNdc: WorldToNdc, screenTexWidth: Int, screenTexHeight: Int) {
		if (cameraApplied) {
			return // the current program already holds this pass's camera (constant across the pass)
		}
		cameraApplied = true
		val locations = current.locations
		GLES20.glUniform4f(locations.worldToNdc, worldToNdc.scaleX, worldToNdc.scaleY, worldToNdc.offsetX, worldToNdc.offsetY)
		GLES20.glUniform2f(locations.screenTexSize, screenTexWidth.toFloat(), screenTexHeight.toFloat())
	}

	override fun drawPuppetMesh(mesh: GpuMesh, deform: DeformUniforms, fragment: FragmentUniforms, textures: DrawTextures) {
		marshalDeformUniforms(current.locations, deform, textures, cornerCellScratch, cornerWeightScratch)
		setFragmentUniforms(current, fragment, textures)
		val glesMesh = mesh as GlesMesh
		GLES30.glBindVertexArray(glesMesh.vao)
		GLES20.glDrawElements(GLES20.GL_TRIANGLES, glesMesh.indexCount, GLES20.GL_UNSIGNED_INT, 0)
	}

	override fun drawGlueMesh(
		mesh: GpuMesh,
		store: DeformedPositionStore,
		baseVertexOffset: Int,
		glueIntensities: FloatArray,
		fragment: FragmentUniforms,
		textures: DrawTextures,
	) {
		val locations = current.locations
		// The weld intensities and the position store are constant across the pass, so bind them once per
		// glue-program bind, not per glue draw.
		//
		// DIVERGENCE: desktop binds a texture BUFFER here (`GL_TEXTURE_BUFFER`, GLES 3.2); this binds the
		// store's RG32F 2D texture to the same unit, and the Es300 glue shader indexes it with texelFetch
		// instead of samplerBuffer.
		if (!glueStateApplied) {
			glueStateApplied = true
			val glesStore = store as GlesDeformedPositionStore
			GLES20.glUniform1fv(locations.glueIntensity, glueIntensities.size, glueIntensities, 0)
			// The Es300 shader indexes the store as a 2D texture and cannot know its row length: it is
			// min(row texels, vertex capacity), so a small model gets a narrower store than the default.
			GLES20.glUniform1i(locations.positionTexWidth, glesStore.width)
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT_POSITION)
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glesStore.texture)
		}
		GLES20.glUniform1i(locations.baseOffset, baseVertexOffset)
		setFragmentUniforms(current, fragment, textures)
		val glesMesh = mesh as GlesMesh
		GLES30.glBindVertexArray(glesMesh.vao)
		GLES20.glDrawElements(GLES20.GL_TRIANGLES, glesMesh.indexCount, GLES20.GL_UNSIGNED_INT, 0)
	}

	override fun drawAtlasPage(atlas: GpuTexture, pageWidth: Float, pageHeight: Float, fragment: FragmentUniforms) {
		val locations = current.locations
		GLES20.glUniform2f(locations.pageSize, pageWidth, pageHeight)
		setFragmentUniforms(current, fragment, DrawTextures().also { it.atlas = atlas })
		GLES30.glBindVertexArray(emptyVao)
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
	}

	override fun drawComposite(composite: CompositeUniforms, textures: DrawTextures) {
		val locations = current.locations
		GLES20.glUniform1i(locations.colorMode, composite.colorMode)
		GLES20.glUniform1i(locations.alphaMode, composite.alphaMode)
		GLES20.glUniform1f(locations.opacity, composite.opacity)
		GLES20.glUniform3f(locations.multiplyColor, composite.multiplyRed, composite.multiplyGreen, composite.multiplyBlue)
		GLES20.glUniform3f(locations.screenColor, composite.screenRed, composite.screenGreen, composite.screenBlue)
		GLES20.glUniform1i(locations.useMask, if (composite.useMask) 1 else 0)
		GLES20.glUniform1i(locations.invertMask, if (composite.invertMask) 1 else 0)
		textures.compositeLayer?.let { bindTexture2D(UNIT_LAYER, it) }
		textures.destinationSnapshot?.let { bindTexture2D(UNIT_DEST, it) }
		if (composite.useMask) {
			textures.maskCoverage?.let { bindTexture2D(UNIT_MASK, it) }
		}
		GLES30.glBindVertexArray(emptyVao)
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
	}

	override fun drawGrid(uniforms: GridUniforms) {
		val locations = current.locations
		val affine = uniforms.worldToNdc
		GLES20.glUniform4f(locations.worldToNdc, affine.scaleX, affine.scaleY, affine.offsetX, affine.offsetY)
		GLES20.glUniform2f(locations.viewportSize, uniforms.viewportWidth.toFloat(), uniforms.viewportHeight.toFloat())
		GLES20.glUniform2f(locations.majorSpacing, uniforms.majorSpacingX, uniforms.majorSpacingY)
		GLES20.glUniform2f(locations.gridOrigin, uniforms.originX, uniforms.originY)
		GLES20.glUniform1f(locations.subdivisions, uniforms.subdivisions.toFloat())
		GLES20.glUniform1f(locations.lineWidthPx, uniforms.lineWidthPx)
		val colors = uniforms.colors
		GLES20.glUniform3f(locations.backgroundColor, colors.backgroundRed, colors.backgroundGreen, colors.backgroundBlue)
		GLES20.glUniform3f(locations.majorColor, colors.majorRed, colors.majorGreen, colors.majorBlue)
		GLES20.glUniform3f(locations.minorColor, colors.minorRed, colors.minorGreen, colors.minorBlue)
		GLES30.glBindVertexArray(emptyVao)
		GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
	}

	override fun drawAxisLine(uniforms: AxisLineUniforms) {
		val locations = current.locations
		GLES20.glUniform1f(locations.linePositionNdc, uniforms.linePositionNdc)
		GLES20.glUniform1f(locations.lineVertical, if (uniforms.vertical) 1f else 0f)
		GLES20.glUniform3f(locations.lineColor, uniforms.red, uniforms.green, uniforms.blue)
		GLES30.glBindVertexArray(emptyVao)
		GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
	}

	override fun end() {
		GLES30.glBindVertexArray(0)
		GLES20.glUseProgram(0)
		// Scissor is per-pass state established in beginRenderPass; dropping it here keeps the
		// between-pass operations (resolve blits, read-backs) unclipped whatever pass ran last.
		GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
	}

	/** Sets a draw's fragment uniforms and binds its atlas / mask textures when present. */
	private fun setFragmentUniforms(pipeline: GlesRenderPipeline, fragment: FragmentUniforms, textures: DrawTextures) {
		val locations = pipeline.locations
		GLES20.glUniform1i(locations.useTexture, if (fragment.useTexture) 1 else 0)
		val atlas = textures.atlas
		if (fragment.useTexture && atlas != null) {
			bindTexture2D(UNIT_ATLAS, atlas)
			// The shader filters linear art itself, past the sampler, so it is told what the sampler would do.
			val glesAtlas = atlas as GlesTexture
			GLES20.glUniform1i(locations.atlasLinear, if (glesAtlas.filter == TextureFilter.Linear) 1 else 0)
			GLES20.glUniform1i(locations.atlasTransparentBorder, if (glesAtlas.wrap == TextureWrap.ClampToTransparentBorder) 1 else 0)
		} else {
			GLES20.glUniform4f(locations.drawColor, fragment.colorRed, fragment.colorGreen, fragment.colorBlue, fragment.colorAlpha)
		}
		GLES20.glUniform1f(locations.opacity, fragment.opacity)
		GLES20.glUniform3f(locations.multiplyColor, fragment.multiplyRed, fragment.multiplyGreen, fragment.multiplyBlue)
		GLES20.glUniform3f(locations.screenColor, fragment.screenRed, fragment.screenGreen, fragment.screenBlue)
		GLES20.glUniform1f(locations.highlight, fragment.highlight)
		GLES20.glUniform3f(locations.highlightColor, fragment.highlightRed, fragment.highlightGreen, fragment.highlightBlue)
		// Sent every draw, like the rest of these - a program that does not declare them resolves -1, and
		// glUniform* with -1 is a defined no-op, so the grid / composite / axis pipelines ignore it.
		GLES20.glUniform3f(locations.uvAffineRow0, fragment.uvAffine[0], fragment.uvAffine[1], fragment.uvAffine[2])
		GLES20.glUniform3f(locations.uvAffineRow1, fragment.uvAffine[3], fragment.uvAffine[4], fragment.uvAffine[5])
		GLES20.glUniform1i(locations.useMask, if (fragment.useMask) 1 else 0)
		GLES20.glUniform1i(locations.invertMask, if (fragment.invertMask) 1 else 0)
		if (fragment.useMask) {
			textures.maskCoverage?.let { bindTexture2D(UNIT_MASK, it) }
		}
	}
}

/** Records the pass-1 deform capture into the shared position store. */
internal class GlesDeformCapturePassEncoder(
	private val pipeline: GlesDeformCapturePipeline,
	private val store: GlesDeformedPositionStore,
) : DeformCapturePassEncoder {
	private val cornerCellScratch: IntBuffer = newIntBuffer(MAX_CORNERS)
	private val cornerWeightScratch: FloatBuffer = newFloatBuffer(MAX_CORNERS)

	override fun captureDeformedPositions(
		mesh: GpuMesh,
		deform: DeformUniforms,
		textures: DrawTextures,
		destinationVertexOffset: Int,
		vertexCount: Int,
	) {
		marshalDeformUniforms(pipeline.locations, deform, textures, cornerCellScratch, cornerWeightScratch)
		GLES30.glBindVertexArray((mesh as GlesMesh).vao)
		// Android's GLES binding takes int offsets here (desktop GL's takes long) - both are byte offsets
		// into the same store buffer, so the arithmetic is identical, only the width differs.
		GLES30.glBindBufferRange(
			GLES30.GL_TRANSFORM_FEEDBACK_BUFFER,
			0,
			store.buffer,
			destinationVertexOffset * 2 * Float.SIZE_BYTES,
			vertexCount * 2 * Float.SIZE_BYTES,
		)
		GLES30.glBeginTransformFeedback(GLES20.GL_POINTS)
		GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
		GLES30.glEndTransformFeedback()
	}

	override fun end() {
		GLES20.glDisable(GLES30.GL_RASTERIZER_DISCARD)
	}
}

/**
 * Marshals a mesh's per-pose deform uniforms and binds its delta (and, for a warp parent, control-point)
 * texture.
 *
 * Shared by the pass-2 draw and the pass-1 capture so the two paths cannot diverge: they feed the exact
 * same deform inputs to the exact same DEFORM_GLSL. Both pass their own reused scratch buffers.
 *
 * @param GlesUniformLocations locations         The bound program's uniform locations.
 * @param DeformUniforms       deform            The per-pose deform inputs.
 * @param DrawTextures         textures          The delta and (warp) control-point textures to bind.
 * @param IntBuffer            cornerCellScratch Reused scratch for the corner-cell uniform array.
 * @param FloatBuffer          cornerWeightScratch Reused scratch for the corner-weight uniform array.
 */
private fun marshalDeformUniforms(
	locations: GlesUniformLocations,
	deform: DeformUniforms,
	textures: DrawTextures,
	cornerCellScratch: IntBuffer,
	cornerWeightScratch: FloatBuffer,
) {
	val cornerCount = minOf(MAX_CORNERS, deform.cornerCount)
	cornerCellScratch.clear()
	cornerWeightScratch.clear()
	for (cornerIndex in 0 until cornerCount) {
		cornerCellScratch.put(deform.cornerCell[cornerIndex])
		cornerWeightScratch.put(deform.cornerWeight[cornerIndex])
	}
	cornerCellScratch.flip()
	cornerWeightScratch.flip()
	GLES20.glUniform1i(locations.cornerCount, cornerCount)
	GLES20.glUniform1iv(locations.cornerCell, cornerCount, cornerCellScratch)
	GLES20.glUniform1fv(locations.cornerWeight, cornerCount, cornerWeightScratch)
	// Blend-shape columns: skip the array uploads entirely for the zero-blend common case.
	val blendCount = minOf(MAX_BLEND_CORNERS, deform.blendCount)
	GLES20.glUniform1i(locations.blendCount, blendCount)
	if (blendCount > 0) {
		GLES20.glUniform1iv(locations.blendCell, blendCount, deform.blendCell, 0)
		GLES20.glUniform1fv(locations.blendWeight, blendCount, deform.blendWeight, 0)
	}
	GLES20.glUniform1i(locations.parentType, deform.parentType)
	if (deform.parentType == 1) {
		GLES20.glUniform1fv(locations.rot, deform.rotation.size, deform.rotation, 0)
	} else if (deform.parentType == 2) {
		GLES20.glUniform1i(locations.warpCols, deform.warpColumns)
		GLES20.glUniform1i(locations.warpRows, deform.warpRows)
		GLES20.glUniform1i(locations.warpBilinear, if (deform.warpBilinear) 1 else 0)
		textures.warpControlPoints?.let { bindTexture2D(UNIT_CP, it) }
	}
	textures.deltaTexture?.let { bindTexture2D(UNIT_DELTA, it) }
}

/** Binds [texture] to the given texture unit as a 2D texture. */
private fun bindTexture2D(unit: Int, texture: GpuTexture) {
	GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
	GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, (texture as GlesTexture).handle)
}

/**
 * Sets the face-culling state for a pipeline. Applied on every pipeline bind (like blend) because
 * GLES cull state is global - a culled drawable's pipeline must not leak culling into the next draw.
 * The front face is CW, matching desktop: corpus rest meshes bake clockwise in the renderer's Y-negated
 * world space.
 *
 * @param Boolean cullBackFaces True to cull back faces; false leaves the mesh double-sided.
 */
private fun applyCull(cullBackFaces: Boolean) {
	if (cullBackFaces) {
		GLES20.glEnable(GLES20.GL_CULL_FACE)
		GLES20.glFrontFace(GLES20.GL_CW)
		GLES20.glCullFace(GLES20.GL_BACK)
	} else {
		GLES20.glDisable(GLES20.GL_CULL_FACE)
	}
}

/** Sets the fixed-function blend state for a pipeline's blend mode. */
private fun applyBlend(blend: PipelineBlend) {
	when (blend) {
		PipelineBlend.Opaque -> GLES20.glDisable(GLES20.GL_BLEND)
		PipelineBlend.Normal -> {
			GLES20.glEnable(GLES20.GL_BLEND)
			GLES20.glBlendFuncSeparate(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
		}
		PipelineBlend.Additive -> {
			GLES20.glEnable(GLES20.GL_BLEND)
			GLES20.glBlendFuncSeparate(GLES20.GL_ONE, GLES20.GL_ONE, GLES20.GL_ZERO, GLES20.GL_ONE)
		}
		PipelineBlend.Multiply -> {
			GLES20.glEnable(GLES20.GL_BLEND)
			GLES20.glBlendFuncSeparate(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA, GLES20.GL_ZERO, GLES20.GL_ONE)
		}
	}
}

/** A direct, native-order int buffer of [capacity] elements - the marshalling scratch these encoders reuse. */
private fun newIntBuffer(capacity: Int): IntBuffer =
	ByteBuffer.allocateDirect(capacity * Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).asIntBuffer()

/** A direct, native-order float buffer of [capacity] elements. */
private fun newFloatBuffer(capacity: Int): FloatBuffer =
	ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()