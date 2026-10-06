package org.umamo.render.gles

import android.opengl.GLES20
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureWrap

// The GLES device's concrete handles - the Android twin of jvmMain's GlHandles. Each wraps the GL names
// its interface hides; nothing outside this package can reach them, which is what keeps GL out of the
// shared renderer. Kept a separate file rather than shared with jvmMain because the two backends sit in
// different source sets and neither may see the other.

/**
 * A GLES texture name, with the filter and wrap it was created with.
 *
 * The puppet fragment shader filters art in-shader through `texelFetch`, which bypasses the sampler, so
 * the encoder reads these to tell the shader what the sampler would have done.
 */
internal class GlesTexture(val handle: Int, val filter: TextureFilter, val wrap: TextureWrap) : GpuTexture

/**
 * A mesh's GLES residency: the VAO plus every buffer it references.
 *
 * The buffers are kept individually, not just the VAO, because they outlive their bindings: an edit
 * re-uploads [positionVbo] / [uvVbo] in place, and freeing the mesh must free all of them. 0 where the
 * mesh has none - an index-less glue anchor has no [indexEbo], a non-glue mesh no [glueVbo].
 */
internal class GlesMesh(
	val vao: Int,
	val positionVbo: Int,
	val uvVbo: Int,
	val glueVbo: Int,
	val indexEbo: Int,
	val vertexCount: Int,
	val indexCount: Int,
) : GpuMesh

/**
 * A render target: its framebuffer plus whichever attachment backs it.
 *
 * Exactly one of [colorTexture] / [colorRenderbuffer] is non-zero, chosen by `RenderTargetSpec.sampled`.
 * A renderbuffer is the cheaper write-only surface but cannot be sampled OR read back directly, which is
 * precisely why the flag exists rather than always allocating a texture.
 */
internal class GlesRenderTarget(
	val framebuffer: Int,
	val colorTexture: Int,
	val colorRenderbuffer: Int,
	val width: Int,
	val height: Int,
) : RenderTarget {
	override val sampledTexture: GpuTexture? = if (colorTexture != 0) GlesTexture(colorTexture, TextureFilter.Linear, TextureWrap.ClampToEdge) else null
}

/**
 * The shared pass-1 position store, repacked for GLES 3.0 as a **2D RG32F texture** instead of desktop
 * GL's texture buffer object (a TBO is GLES 3.2, and 3.0 is the baseline this port targets).
 *
 * Three names, because a texture cannot be a transform-feedback target and ES 3.0 offers no
 * buffer→texture view:
 *
 *  - [buffer]   the TF destination: pass 1 writes `outWorld` (vec2 per vertex) here
 *  - [staging]  a PBO the buffer is copied through when refreshing [texture]: TF writes a buffer, so the
 *               bytes must go buffer → PBO → texture. Bound as GL_PIXEL_UNPACK_BUFFER, the copy is
 *               GPU-side with no client round-trip.
 *  - [texture]  the RG32F 2D texture pass 2 samples with `texelFetch(store, ivec2(index % width,
 *               index / width), 0)`
 *
 * Vertex `i` lives at texel `(i % width, i / width)`; [width] is the fixed row length both the shader and
 * this class must agree on, which is why it is carried here rather than derived per draw.
 *
 * Desktop keeps this as one buffer + one texture-buffer view (`GlDeformedPositionStore`); nothing above
 * the device can tell the difference.
 */
internal class GlesDeformedPositionStore(
	val buffer: Int,
	val staging: Int,
	val texture: Int,
	val width: Int,
	val height: Int,
	val vertexCapacity: Int,
) : DeformedPositionStore

/**
 * Every uniform any of this backend's programs declares, resolved once at pipeline creation.
 *
 * One flat set rather than a per-purpose struct, because GL makes it free: `glGetUniformLocation` returns
 * -1 for a uniform a program does not declare (or that its compiler optimised away), and `glUniform*`
 * with -1 is a defined no-op. So the grid pipeline simply has -1 for every puppet uniform and setting
 * them costs a branch in the driver.
 */
internal class GlesUniformLocations(program: Int) {
	// Per-pass. viewportSize is the grid program's genuine viewport extent; screenTexSize is the
	// puppet/composite programs' screen-space texture divisor (the side targets' allocated size,
	// which the grow-only capacity can hold above the viewport size).
	val worldToNdc = GLES20.glGetUniformLocation(program, "worldToNdc")
	val viewportSize = GLES20.glGetUniformLocation(program, "viewportSize")
	val screenTexSize = GLES20.glGetUniformLocation(program, "screenTexSize")

	// Samplers
	val atlas = GLES20.glGetUniformLocation(program, "atlas")
	val maskTexture = GLES20.glGetUniformLocation(program, "maskTexture")
	val deltaTex = GLES20.glGetUniformLocation(program, "deltaTex")
	val cpTex = GLES20.glGetUniformLocation(program, "cpTex")
	val positionBuffer = GLES20.glGetUniformLocation(program, "positionBuffer")
	// The Es300 glue shader turns a linear vertex index into a texel coordinate with these two: the
	// store is a 2D texture there, so the row length has to be told to it (see glueVertexShader).
	val positionTexWidth = GLES20.glGetUniformLocation(program, "positionTexWidth")

	// Deform
	val cornerCount = GLES20.glGetUniformLocation(program, "cornerCount")
	val cornerCell = GLES20.glGetUniformLocation(program, "cornerCell")
	val cornerWeight = GLES20.glGetUniformLocation(program, "cornerWeight")
	val blendCount = GLES20.glGetUniformLocation(program, "blendCount")
	val blendCell = GLES20.glGetUniformLocation(program, "blendCell")
	val blendWeight = GLES20.glGetUniformLocation(program, "blendWeight")
	val parentType = GLES20.glGetUniformLocation(program, "parentType")
	val rot = GLES20.glGetUniformLocation(program, "rot")
	val warpCols = GLES20.glGetUniformLocation(program, "warpCols")
	val warpRows = GLES20.glGetUniformLocation(program, "warpRows")
	val warpBilinear = GLES20.glGetUniformLocation(program, "warpBilinear")

	// Glue
	val baseOffset = GLES20.glGetUniformLocation(program, "baseOffset")
	val glueIntensity = GLES20.glGetUniformLocation(program, "glueIntensity")

	// Fragment
	val useTexture = GLES20.glGetUniformLocation(program, "useTexture")
	val drawColor = GLES20.glGetUniformLocation(program, "drawColor")
	val opacity = GLES20.glGetUniformLocation(program, "opacity")
	val useMask = GLES20.glGetUniformLocation(program, "useMask")
	val invertMask = GLES20.glGetUniformLocation(program, "invertMask")
	val highlight = GLES20.glGetUniformLocation(program, "highlight")
	val highlightColor = GLES20.glGetUniformLocation(program, "highlightColor")
	val uvAffineRow0 = GLES20.glGetUniformLocation(program, "uvAffineRow0")
	val uvAffineRow1 = GLES20.glGetUniformLocation(program, "uvAffineRow1")
	val atlasLinear = GLES20.glGetUniformLocation(program, "atlasLinear")
	val atlasTransparentBorder = GLES20.glGetUniformLocation(program, "atlasTransparentBorder")

	// Atlas page
	val pageSize = GLES20.glGetUniformLocation(program, "pageSize")

	// Layer composite
	val layerTexture = GLES20.glGetUniformLocation(program, "layerTexture")
	val destTexture = GLES20.glGetUniformLocation(program, "destTexture")
	val colorMode = GLES20.glGetUniformLocation(program, "colorMode")
	val alphaMode = GLES20.glGetUniformLocation(program, "alphaMode")
	val multiplyColor = GLES20.glGetUniformLocation(program, "multiplyColor")
	val screenColor = GLES20.glGetUniformLocation(program, "screenColor")

	// Grid backdrop
	val majorSpacing = GLES20.glGetUniformLocation(program, "majorSpacing")
	val subdivisions = GLES20.glGetUniformLocation(program, "subdivisions")
	val lineWidthPx = GLES20.glGetUniformLocation(program, "lineWidthPx")
	val backgroundColor = GLES20.glGetUniformLocation(program, "backgroundColor")
	val majorColor = GLES20.glGetUniformLocation(program, "majorColor")
	val minorColor = GLES20.glGetUniformLocation(program, "minorColor")
	val gridOrigin = GLES20.glGetUniformLocation(program, "gridOrigin")

	// Axis line
	val linePositionNdc = GLES20.glGetUniformLocation(program, "linePositionNdc")
	val lineVertical = GLES20.glGetUniformLocation(program, "lineVertical")
	val lineColor = GLES20.glGetUniformLocation(program, "lineColor")
}

/** A linked draw program with its blend, cull state, and resolved uniform locations. */
internal class GlesRenderPipeline(
	val program: Int,
	val blend: PipelineBlend,
	val cullBackFaces: Boolean,
	val locations: GlesUniformLocations,
) : RenderPipeline

/** The transform-feedback program that captures deformed positions without rasterizing. */
internal class GlesDeformCapturePipeline(
	val program: Int,
	val locations: GlesUniformLocations,
) : DeformCapturePipeline