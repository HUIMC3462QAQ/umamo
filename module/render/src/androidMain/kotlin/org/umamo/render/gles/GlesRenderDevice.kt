package org.umamo.render.gles

import android.opengl.GLES20
import android.opengl.GLES30
import org.umamo.format.raster.RasterImage
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.ReadbackTicket
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat
import org.umamo.render.device.TextureWrap
import org.umamo.render.glsl.GlslDialect
import org.umamo.render.glsl.atlasPageVertexShader
import org.umamo.render.glsl.axisFragmentShader
import org.umamo.render.glsl.axisVertexShader
import org.umamo.render.glsl.compositeFragmentShader
import org.umamo.render.glsl.compositeVertexShader
import org.umamo.render.glsl.glueVertexShader
import org.umamo.render.glsl.gridFragmentShader
import org.umamo.render.glsl.gridVertexShader
import org.umamo.render.glsl.puppetFragmentShader
import org.umamo.render.glsl.puppetVertexShader
import org.umamo.render.glsl.tfDeformVertexShader
import org.umamo.render.glsl.tfDiscardFragmentShader
import org.umamo.render.puppet.GlueVertexAttributes
import org.umamo.render.puppet.flipRowsVertically
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The Android GLES 3.0 [RenderDevice], over `android.opengl`.
 *
 * A near-transliteration of the desktop `GlRenderDevice` (jvmMain, `org.umamo.render.gl`): the calls are
 * the same, differing only in binding style - LWJGL statics returning values vs `GLES30`'s out-parameter
 * form (`glGenTextures(1, ids, 0)` instead of `glGenTextures()`). `PuppetRenderer` needs no changes at
 * all; the shared GLSL comes from `org.umamo.render.glsl` with [GlslDialect.Es300].
 *
 * Three places genuinely diverge from desktop, each marked at its method:
 *
 *  1. [createDeformedPositionStore] - desktop backs the pass-1 store with a texture buffer object, and
 *     TBOs are GLES 3.2 while this port's baseline is 3.0 (see `GlesDeformedPositionStore`).
 *  2. [setTextureParameters] - ES 3.0 has no `GL_CLAMP_TO_BORDER`, so `ClampToTransparentBorder` falls
 *     back to `GL_CLAMP_TO_EDGE`. The shader's `atlasTransparentBorder` uniform is what actually keeps
 *     the outermost half-texel from sampling outside the art.
 *  3. [createFloatTexture] / [updateFloatTexture] - RG32F is a valid ES 3.0 texture format but is not
 *     filterable without `OES_texture_float_linear`. These textures are only ever read with `texelFetch`,
 *     which bypasses the sampler, so NEAREST is used and the filter argument is ignored.
 *
 * Every method assumes a current GLES context on the calling thread, and the device is used from ONE
 * thread only - the render thread. The host owns the context (an EGL surface or a `GLSurfaceView`).
 *
 * Android GLES 3.0 デバイス。デスクトップ GL 実装のほぼ逐語的な移植。ストアだけが 3.2 の TBO を
 * 使えないため RG32F の 2D テクスチャに詰め替える。
 */
class GlesRenderDevice : RenderDevice {
	// An empty VAO for the attribute-less draws (grid, axis lines, atlas page): GLES 3.0 still requires a
	// bound VAO even when the vertex shader synthesises its positions from gl_VertexID.
	private var emptyVao = 0

	// Pipelines are immutable and reused every frame, so they are cached by their spec rather than rebuilt.
	private val renderPipelines = HashMap<RenderPipelineSpec, GlesRenderPipeline>()
	private var deformCapturePipeline: GlesDeformCapturePipeline? = null

	// Out-parameter scratch. Single-threaded by contract, so one array serves every generation call.
	private val idScratch = IntArray(1)
	private val intScratch = IntArray(2)

	private fun genTexture(): Int {
		GLES20.glGenTextures(1, idScratch, 0)
		return idScratch[0]
	}

	private fun genBuffer(): Int {
		GLES20.glGenBuffers(1, idScratch, 0)
		return idScratch[0]
	}

	private fun ensureEmptyVao(): Int {
		if (emptyVao == 0) {
			GLES30.glGenVertexArrays(1, idScratch, 0)
			emptyVao = idScratch[0]
		}
		return emptyVao
	}

	override fun createTexture(
		width: Int,
		height: Int,
		format: TextureFormat,
		filter: TextureFilter,
		pixels: ByteArray?,
		wrap: TextureWrap,
	): GpuTexture {
		val handle = genTexture()
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
		setTextureParameters(filter, wrap)
		GLES20.glTexImage2D(
			GLES20.GL_TEXTURE_2D,
			0,
			internalFormatOf(format),
			width,
			height,
			0,
			pixelFormatOf(format),
			pixelTypeOf(format),
			pixels?.let { byteBufferOf(it) },
		)
		return GlesTexture(handle, filter, wrap)
	}

	override fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture {
		val handle = genTexture()
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
		// NEAREST regardless of `filter`: RG32F is not filterable in core ES 3.0, and every read of these
		// textures is an in-shader texelFetch, which does not consult the sampler anyway.
		setTextureParameters(TextureFilter.Nearest, TextureWrap.ClampToEdge)
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG32F, width, height, 0, GLES30.GL_RG, GLES20.GL_FLOAT, floatBufferOf(texels))
		return GlesTexture(handle, TextureFilter.Nearest, TextureWrap.ClampToEdge)
	}

	override fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray) {
		val glesTexture = texture as GlesTexture
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, glesTexture.handle)
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG32F, width, height, 0, GLES30.GL_RG, GLES20.GL_FLOAT, floatBufferOf(texels))
	}

	override fun createMesh(spec: MeshSpec): GpuMesh {
		GLES30.glGenVertexArrays(1, idScratch, 0)
		val vao = idScratch[0]
		GLES30.glBindVertexArray(vao)

		val positionVbo = uploadArrayBuffer(spec.restPositions, GLES20.GL_DYNAMIC_DRAW)
		GLES20.glEnableVertexAttribArray(0)
		GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)

		val vertexCount = spec.restPositions.size / 2
		// A mesh whose UVs are shorter than its vertices gets a zero-filled UV buffer of the right length,
		// so an in-place UV re-upload later has a target of the expected size.
		val safeUvs = if (spec.uvs.size >= vertexCount * 2) spec.uvs else FloatArray(vertexCount * 2)
		val uvVbo = uploadArrayBuffer(safeUvs, GLES20.GL_DYNAMIC_DRAW)
		GLES20.glEnableVertexAttribArray(1)
		GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)

		var glueVbo = 0
		spec.glueAttributes?.let { attributes ->
			glueVbo = genBuffer()
			GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, glueVbo)
			GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, attributes.partnerIndex.size * 3 * Int.SIZE_BYTES, interleaveGlueAttributes(attributes), GLES20.GL_STATIC_DRAW)
			val stride = 3 * Int.SIZE_BYTES
			GLES20.glEnableVertexAttribArray(2)
			GLES30.glVertexAttribIPointer(2, 1, GLES20.GL_INT, stride, 0)
			GLES20.glEnableVertexAttribArray(3)
			GLES30.glVertexAttribIPointer(3, 1, GLES20.GL_INT, stride, Int.SIZE_BYTES)
			GLES20.glEnableVertexAttribArray(4)
			GLES20.glVertexAttribPointer(4, 1, GLES20.GL_FLOAT, false, stride, 2 * Int.SIZE_BYTES)
		}

		var indexEbo = 0
		if (spec.indices.isNotEmpty()) {
			indexEbo = genBuffer()
			GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexEbo)
			GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, spec.indices.size * Int.SIZE_BYTES, intBufferOf(spec.indices), GLES20.GL_STATIC_DRAW)
		}

		GLES30.glBindVertexArray(0)
		return GlesMesh(vao, positionVbo, uvVbo, glueVbo, indexEbo, vertexCount, spec.indices.size)
	}

	override fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray) {
		subDataArrayBuffer((mesh as GlesMesh).positionVbo, restPositions)
	}

	override fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray) {
		subDataArrayBuffer((mesh as GlesMesh).uvVbo, uvs)
	}

	override fun createRenderTarget(spec: RenderTargetSpec): RenderTarget {
		GLES30.glGenFramebuffers(1, idScratch, 0)
		val framebuffer = idScratch[0]
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
		var colorTexture = 0
		var colorRenderbuffer = 0
		if (spec.sampled) {
			colorTexture = genTexture()
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexture)
			setTextureParameters(TextureFilter.Linear, TextureWrap.ClampToEdge)
			GLES20.glTexImage2D(
				GLES20.GL_TEXTURE_2D,
				0,
				internalFormatOf(spec.format),
				spec.width,
				spec.height,
				0,
				pixelFormatOf(spec.format),
				pixelTypeOf(spec.format),
				null,
			)
			GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTexture, 0)
		} else {
			// A write-only surface: cheaper, but cannot be sampled or read back directly.
			GLES30.glGenRenderbuffers(1, idScratch, 0)
			colorRenderbuffer = idScratch[0]
			GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, colorRenderbuffer)
			GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, internalFormatOf(spec.format), spec.width, spec.height)
			GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, colorRenderbuffer)
		}
		return GlesRenderTarget(framebuffer, colorTexture, colorRenderbuffer, spec.width, spec.height)
	}

	/**
	 * Allocates the pass-1 store as a **2D RG32F texture** - the port's one real divergence from desktop,
	 * which uses a texture buffer object (GLES 3.2; this port's baseline is 3.0).
	 *
	 * A texture cannot be a transform-feedback target, so three names are needed: the TF buffer pass 1
	 * writes, a PBO to copy it through, and the texture pass 2 samples. [syncDeformedPositionStore] does
	 * the buffer → PBO → texture hop with no client round-trip.
	 *
	 * The row length is fixed here and must match the shader's `texelFetch` indexing (`i % width`,
	 * `i / width`). 1024 keeps the store square-ish for typical glue vertex counts and stays well inside
	 * every ES 3.0 implementation's `GL_MAX_TEXTURE_SIZE`.
	 *
	 * @param Int vertexCapacity The total glue vertex count across every glue mesh.
	 * @return DeformedPositionStore The store.
	 */
	override fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore {
		val width = minOf(STORE_ROW_TEXELS, maxOf(1, vertexCapacity))
		val height = maxOf(1, (vertexCapacity + width - 1) / width)
		val bytes = vertexCapacity * 2 * Float.SIZE_BYTES

		val buffer = genBuffer()
		GLES30.glBindBuffer(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER, buffer)
		GLES20.glBufferData(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER, bytes, null, GLES30.GL_DYNAMIC_COPY)
		GLES30.glBindBuffer(GLES30.GL_TRANSFORM_FEEDBACK_BUFFER, 0)

		val staging = genBuffer()
		GLES20.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, staging)
		GLES20.glBufferData(GLES30.GL_PIXEL_UNPACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
		GLES20.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)

		val texture = genTexture()
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
		setTextureParameters(TextureFilter.Nearest, TextureWrap.ClampToEdge)
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG32F, width, height, 0, GLES30.GL_RG, GLES20.GL_FLOAT, null)

		return GlesDeformedPositionStore(buffer, staging, texture, width, height, vertexCapacity)
	}

	/**
	 * Refreshes the store's texture from its transform-feedback buffer.
	 *
	 * Called by the frame encoder on the pose-change frames where pass 1 ran, between the capture pass and
	 * the glue draw that samples it - the same place desktop's `barrier(store)` sits.
	 *
	 * The copy is buffer → PBO → texture, all server-side: with a PBO bound, `glTexSubImage2D`'s data
	 * pointer is an OFFSET into it, and `null` is offset 0 (the whole buffer is uploaded, so 0 is what is
	 * wanted). Android's GLES binding exposes no int-offset overload of `glTexSubImage2D`, which is why
	 * the offset cannot be passed explicitly - see the port notes in the class docblock.
	 *
	 * @param GlesDeformedPositionStore store The store to refresh.
	 */
	internal fun syncDeformedPositionStore(store: GlesDeformedPositionStore) {
		val bytes = store.vertexCapacity * 2 * Float.SIZE_BYTES
		// Buffer -> buffer first, through the copy bind points (glCopyBufferSubData takes binding TARGETS,
		// so the staging PBO is bound to COPY_WRITE_BUFFER here, not yet to PIXEL_UNPACK_BUFFER).
		GLES20.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, store.buffer)
		GLES20.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, store.staging)
		GLES30.glCopyBufferSubData(GLES30.GL_COPY_READ_BUFFER, GLES30.GL_COPY_WRITE_BUFFER, 0, 0, bytes)
		// Then the PBO becomes the unpack source for the texture upload. With a PBO bound, the data
		// argument of glTexSubImage2D is a BYTE OFFSET into it, and null is offset 0 - which is what is
		// wanted, since the whole staging buffer is uploaded. (Android's binding has no int-offset
		// overload of glTexSubImage2D; see the port notes in the class docblock. If a device rejects a
		// null client pointer here, the fallback is to map the PBO and upload from client memory.)
		GLES20.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, store.staging)
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, store.texture)
		// Unpack alignment 4 suits an 8-byte RG32F texel, and GL_UNPACK_ROW_LENGTH stays 0 (= the copy
		// width), so the buffer's tightly packed rows land in the texture's rows unmodified.
		GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, store.width, store.height, GLES30.GL_RG, GLES20.GL_FLOAT, null)
		GLES20.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
		GLES20.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, 0)
		GLES20.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, 0)
	}

	override fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline =
		renderPipelines.getOrPut(spec) {
			val (vertexSource, fragmentSource) = sourcesFor(spec.purpose)
			val program = linkProgram(vertexSource, fragmentSource, spec.purpose.name)
			GlesRenderPipeline(program, spec.blend, spec.cullBackFaces, GlesUniformLocations(program))
		}

	override fun createDeformCapturePipeline(): DeformCapturePipeline =
		deformCapturePipeline ?: run {
			val program = attachShaders(tfDeformVertexShader(DIALECT), tfDiscardFragmentShader(DIALECT))
			GLES30.glTransformFeedbackVaryings(program, arrayOf("outWorld"), GLES30.GL_INTERLEAVED_ATTRIBS)
			GLES20.glLinkProgram(program)
			val linked = IntArray(1)
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
			check(linked[0] != 0) { "deform-capture program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
			GlesDeformCapturePipeline(program, GlesUniformLocations(program)).also { deformCapturePipeline = it }
		}

	override fun destroyTexture(texture: GpuTexture) {
		idScratch[0] = (texture as GlesTexture).handle
		GLES20.glDeleteTextures(1, idScratch, 0)
	}

	override fun destroyMesh(mesh: GpuMesh) {
		val glesMesh = mesh as GlesMesh
		GLES30.glDeleteVertexArrays(1, intArrayOf(glesMesh.vao), 0)
		idScratch[0] = glesMesh.positionVbo
		GLES20.glDeleteBuffers(1, idScratch, 0)
		idScratch[0] = glesMesh.uvVbo
		GLES20.glDeleteBuffers(1, idScratch, 0)
		if (glesMesh.glueVbo != 0) {
			idScratch[0] = glesMesh.glueVbo
			GLES20.glDeleteBuffers(1, idScratch, 0)
		}
		if (glesMesh.indexEbo != 0) {
			idScratch[0] = glesMesh.indexEbo
			GLES20.glDeleteBuffers(1, idScratch, 0)
		}
	}

	override fun destroyRenderTarget(target: RenderTarget) {
		val glesTarget = target as GlesRenderTarget
		idScratch[0] = glesTarget.framebuffer
		GLES30.glDeleteFramebuffers(1, idScratch, 0)
		if (glesTarget.colorTexture != 0) {
			idScratch[0] = glesTarget.colorTexture
			GLES20.glDeleteTextures(1, idScratch, 0)
		}
		if (glesTarget.colorRenderbuffer != 0) {
			idScratch[0] = glesTarget.colorRenderbuffer
			GLES30.glDeleteRenderbuffers(1, idScratch, 0)
		}
	}

	override fun beginFrame(): FrameEncoder = GlesFrameEncoder(this, ensureEmptyVao())

	override fun resolve(source: RenderTarget, destination: RenderTarget, region: ScissorRect?) {
		val glesSource = source as GlesRenderTarget
		val glesDestination = destination as GlesRenderTarget
		resolveUsed(glesSource, glesSource.width, glesSource.height, glesDestination, glesDestination.width, glesDestination.height, region)
	}

	override fun resolveUsed(
		source: RenderTarget,
		sourceUsedWidth: Int,
		sourceUsedHeight: Int,
		destination: RenderTarget,
		destinationUsedWidth: Int,
		destinationUsedHeight: Int,
		region: ScissorRect?,
	) {
		val glesSource = source as GlesRenderTarget
		val glesDestination = destination as GlesRenderTarget
		GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, glesSource.framebuffer)
		GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, glesDestination.framebuffer)
		// Content sits at GL (0, 0): every pass runs glViewport(0, 0, used, used), so the used region IS
		// the bottom-left rows - and GLES reads bottom-up exactly like GL. GL_LINEAR makes the downscale a
		// box filter (the supersample resolve); glBlitFramebuffer is core in ES 3.0.
		if (region == null) {
			GLES30.glBlitFramebuffer(
				0,
				0,
				sourceUsedWidth,
				sourceUsedHeight,
				0,
				0,
				destinationUsedWidth,
				destinationUsedHeight,
				GLES20.GL_COLOR_BUFFER_BIT,
				GLES20.GL_LINEAR,
			)
		} else {
			// A same-size sub-rectangle copy (the composite path's snapshot). The rect is
			// top-left-origin per the ScissorRect contract; GLES blits bottom-up, so the flip is against
			// the USED height - flipping against a larger allocation would land above the content.
			val bottomUpY = sourceUsedHeight - region.y - region.height
			GLES30.glBlitFramebuffer(
				region.x,
				bottomUpY,
				region.x + region.width,
				bottomUpY + region.height,
				region.x,
				bottomUpY,
				region.x + region.width,
				bottomUpY + region.height,
				GLES20.GL_COLOR_BUFFER_BIT,
				GLES20.GL_NEAREST,
			)
		}
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, glesDestination.framebuffer)
	}

	/** A recyclable pixel-buffer object and its current byte capacity. */
	private class Pbo(val id: Int, var capacity: Int)

	/**
	 * An in-flight asynchronous read-back: the staging PBO, the fence gating it, and the read's extent.
	 * [spent] flips when the ticket is consumed or cancelled, making both one-shot and idempotent.
	 */
	private class GlesReadbackTicket(
		val pbo: Pbo,
		val fence: Long,
		val width: Int,
		val height: Int,
		var spent: Boolean = false,
	) : ReadbackTicket

	// Staging PBOs are recycled rather than freed: a viewport read-back runs per frame, and reallocating
	// a multi-megabyte buffer 60x/sec is churn the pool exists to avoid. Render-thread only.
	private val freePbos = ArrayDeque<Pbo>()

	override fun beginReadback(target: RenderTarget): ReadbackTicket {
		val glesTarget = target as GlesRenderTarget
		return beginReadback(glesTarget, glesTarget.width, glesTarget.height)
	}

	override fun beginReadback(target: RenderTarget, usedWidth: Int, usedHeight: Int): ReadbackTicket {
		val glesTarget = target as GlesRenderTarget
		GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, glesTarget.framebuffer)
		val pbo = acquirePbo(usedWidth * usedHeight * 4)
		GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo.id)
		// With a PBO bound, the last argument is a BUFFER OFFSET, not a client pointer - glReadPixels
		// returns immediately and the copy happens asynchronously on the GPU timeline. The used region is
		// the bottom-left rows (passes viewport at the origin), so the read starts at (0, 0).
		GLES30.glReadPixels(0, 0, usedWidth, usedHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
		GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
		val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
		GLES20.glFlush() // submit the commands + fence so the fence can eventually signal
		return GlesReadbackTicket(pbo, fence, usedWidth, usedHeight)
	}

	override fun pollReadback(ticket: ReadbackTicket): RasterImage? {
		val glesTicket = ticket as GlesReadbackTicket
		check(!glesTicket.spent) { "pollReadback on a spent ticket" }
		val status = GLES30.glClientWaitSync(glesTicket.fence, 0, 0L)
		if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) {
			return null // still in flight; the ticket stays live to poll again
		}
		// The fence signaled, so the read is done and the ticket is consumed HERE - exactly once, whether or
		// not the map below succeeds. Returning null past this point would make the caller treat a consumed
		// ticket as still-in-flight and re-poll it, tripping the spent check and killing the render thread.
		glesTicket.spent = true
		GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, glesTicket.pbo.id)
		val rowBytes = glesTicket.width * 4
		// ES has no glMapBuffer; the Range form is the one that exists. It returns null on failure, which
		// the zero-filled fallback below covers.
		val mapped: ByteBuffer? =
			GLES30.glMapBufferRange(
				GLES30.GL_PIXEL_PACK_BUFFER,
				0,
				rowBytes * glesTicket.height,
				GLES30.GL_MAP_READ_BIT,
			) as? ByteBuffer
		val topDown = ByteArray(rowBytes * glesTicket.height)
		if (mapped != null) {
			// Flip the GL bottom-up rows straight out of the mapped PBO into the API's top-first contract -
			// one bulk get per row, the direct-buffer equivalent of System.arraycopy.
			for (row in 0 until glesTicket.height) {
				mapped.position((glesTicket.height - 1 - row) * rowBytes)
				mapped.get(topDown, row * rowBytes, rowBytes)
			}
			GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER) // only when the map actually succeeded
		}
		// A map failure (a driver error that should never happen) leaves topDown zero-filled: one black
		// frame rather than a permanently frozen viewport, and null keeps meaning "still in flight".
		GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
		GLES30.glDeleteSync(glesTicket.fence)
		freePbos.addLast(glesTicket.pbo)
		return RasterImage(glesTicket.width, glesTicket.height, topDown)
	}

	override fun cancelReadback(ticket: ReadbackTicket) {
		val glesTicket = ticket as GlesReadbackTicket
		if (glesTicket.spent) {
			return
		}
		glesTicket.spent = true
		GLES30.glDeleteSync(glesTicket.fence)
		freePbos.addLast(glesTicket.pbo)
	}

	/**
	 * Acquires a PBO with at least [capacity] bytes from the free pool (allocating/growing as needed).
	 * GL_STREAM_READ: written by GL, read once by the client.
	 *
	 * @param Int capacity The minimum byte capacity needed.
	 * @return Pbo The acquired PBO.
	 */
	private fun acquirePbo(capacity: Int): Pbo {
		val pbo = freePbos.removeFirstOrNull() ?: Pbo(genBuffer(), 0)
		if (pbo.capacity < capacity) {
			GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo.id)
			GLES20.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, capacity, null, GLES30.GL_STREAM_READ)
			GLES20.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
			pbo.capacity = capacity
		}
		return pbo
	}

	override fun readPixels(target: RenderTarget): RasterImage {
		val glesTarget = target as GlesRenderTarget
		return readPixels(glesTarget, glesTarget.width, glesTarget.height)
	}

	override fun readPixels(target: RenderTarget, usedWidth: Int, usedHeight: Int): RasterImage {
		val glesTarget = target as GlesRenderTarget
		GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, glesTarget.framebuffer)
		val pixels = ByteBuffer.allocateDirect(usedWidth * usedHeight * 4).order(ByteOrder.nativeOrder())
		// The used region is the bottom-left rows (passes viewport at the origin): read from (0, 0).
		GLES20.glReadPixels(0, 0, usedWidth, usedHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
		val bottomUp = ByteArray(usedWidth * usedHeight * 4)
		pixels.get(bottomUp)
		// GLES reads bottom-up; RasterImage is top-first. The flip is this backend's; no caller asks.
		return RasterImage(usedWidth, usedHeight, flipRowsVertically(bottomUp, usedWidth, usedHeight))
	}

	override fun maxRenderTargetSize(): Int {
		GLES20.glGetIntegerv(GLES20.GL_MAX_VIEWPORT_DIMS, intScratch, 0)
		GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, idScratch, 0)
		val textureSize = idScratch[0]
		GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, idScratch, 0)
		val renderbufferSize = idScratch[0]
		return minOf(textureSize, renderbufferSize, intScratch[0], intScratch[1])
	}

	override fun describeBackend(): String {
		val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
		val version = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
		val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown"
		val glsl = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION) ?: "unknown"
		return "renderer=$renderer | version=$version | vendor=$vendor | glsl=$glsl"
	}

	/** The (vertex, fragment) GLSL for a pipeline purpose - this backend's shader library, keyed by purpose. */
	private fun sourcesFor(purpose: PipelinePurpose): Pair<String, String> =
		when (purpose) {
			PipelinePurpose.PuppetDeformDraw -> puppetVertexShader(DIALECT) to puppetFragmentShader(DIALECT)
			PipelinePurpose.PuppetGlueDraw -> glueVertexShader(DIALECT) to puppetFragmentShader(DIALECT)
			PipelinePurpose.AtlasPageDraw -> atlasPageVertexShader(DIALECT) to puppetFragmentShader(DIALECT)
			PipelinePurpose.GridBackdrop -> gridVertexShader(DIALECT) to gridFragmentShader(DIALECT)
			PipelinePurpose.WorldAxisLine -> axisVertexShader(DIALECT) to axisFragmentShader(DIALECT)
			PipelinePurpose.Composite -> compositeVertexShader(DIALECT) to compositeFragmentShader(DIALECT)
		}

	/**
	 * Sets nearest/linear filtering and the requested wrapping on the currently-bound 2D texture.
	 *
	 * DIVERGENCE: `GL_CLAMP_TO_BORDER` does not exist in ES 3.0, so [TextureWrap.ClampToTransparentBorder]
	 * clamps to the edge instead. The transparent border is then emulated in the fragment shader through
	 * the `atlasTransparentBorder` uniform, which the atlases' own padding already makes safe; the desktop
	 * path's per-texture border color has no ES 3.0 equivalent to set.
	 *
	 * @param TextureFilter filter The filtering mode.
	 * @param TextureWrap   wrap   The wrapping mode, applied to both axes.
	 */
	private fun setTextureParameters(filter: TextureFilter, wrap: TextureWrap) {
		val glFilter = if (filter == TextureFilter.Nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, glFilter)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, glFilter)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
	}

	private fun internalFormatOf(format: TextureFormat): Int = if (format == TextureFormat.Rgba8) GLES30.GL_RGBA8 else GLES30.GL_RG32F

	private fun pixelFormatOf(format: TextureFormat): Int = if (format == TextureFormat.Rgba8) GLES20.GL_RGBA else GLES30.GL_RG

	private fun pixelTypeOf(format: TextureFormat): Int = if (format == TextureFormat.Rgba8) GLES20.GL_UNSIGNED_BYTE else GLES20.GL_FLOAT

	/** Uploads a float array into a fresh GL_ARRAY_BUFFER with the given usage; leaves it bound. */
	private fun uploadArrayBuffer(values: FloatArray, usage: Int): Int {
		val vbo = genBuffer()
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, values.size * Float.SIZE_BYTES, floatBufferOf(values), usage)
		return vbo
	}

	// Reusable scratch for in-place VBO re-uploads, grown on demand so a live preview edit never allocates.
	private var uploadScratch: FloatBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()

	/** Re-specifies a whole array buffer's contents in place, reusing the scratch buffer. */
	private fun subDataArrayBuffer(vbo: Int, values: FloatArray) {
		if (uploadScratch.capacity() < values.size) {
			uploadScratch = ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
		}
		uploadScratch.clear()
		uploadScratch.put(values).flip()
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
		GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, values.size * Float.SIZE_BYTES, uploadScratch)
	}

	/** The row length of the pass-1 store's 2D texture; the glue shader's texelFetch indexing depends on it. */
	companion object {
		internal const val STORE_ROW_TEXELS = 1024
	}
}

/** This backend is GLES 3.0; the shared GLSL is emitted for it. */
private val DIALECT = GlslDialect.Es300

/**
 * Interleaves a mesh's planned weld attributes into the 12-byte-per-vertex form the glue VAO reads:
 * int partner global index, int glue index, float weld weight, in the native byte order this file's
 * buffers allocate - which is what `glVertexAttribIPointer` reads back.
 *
 * The layout is GL's; `org.umamo.render.puppet.planGlueLayout` decided the values. A free function, not
 * a device method, since it touches no device state.
 *
 * @param GlueVertexAttributes attributes The mesh's planned per-vertex weld attributes.
 * @return ByteBuffer The interleaved buffer, flipped and ready to upload.
 */
private fun interleaveGlueAttributes(attributes: GlueVertexAttributes): ByteBuffer {
	val vertexCount = attributes.partnerIndex.size
	val buffer = ByteBuffer.allocateDirect(vertexCount * 3 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
	for (vertexIndex in 0 until vertexCount) {
		buffer.putInt(attributes.partnerIndex[vertexIndex])
		buffer.putInt(attributes.glueIndex[vertexIndex])
		buffer.putFloat(attributes.weldWeight[vertexIndex])
	}
	buffer.flip()
	return buffer
}

/** A direct, native-order byte buffer over [bytes], flipped and ready to upload. */
private fun byteBufferOf(bytes: ByteArray): ByteBuffer =
	ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
		put(bytes)
		flip()
	}

/** A direct, native-order float buffer over [values], flipped and ready to upload. */
private fun floatBufferOf(values: FloatArray): FloatBuffer =
	ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
		put(values)
		flip()
	}

/** A direct, native-order int buffer over [values], flipped and ready to upload. */
private fun intBufferOf(values: IntArray): java.nio.IntBuffer =
	ByteBuffer.allocateDirect(values.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder()).asIntBuffer().apply {
		put(values)
		flip()
	}

/**
 * Links a GLES program from vertex + fragment source, throwing with the info log on failure.
 *
 * @param String vertexSource   The vertex-stage GLSL.
 * @param String fragmentSource The fragment-stage GLSL.
 * @param String label          A name for the failure message.
 * @return Int The linked program handle.
 */
internal fun linkProgram(vertexSource: String, fragmentSource: String, label: String): Int {
	val program = attachShaders(vertexSource, fragmentSource)
	GLES20.glLinkProgram(program)
	val linked = IntArray(1)
	GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
	check(linked[0] != 0) { "$label program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
	return program
}

/** Compiles and attaches both stages onto a fresh program (not yet linked, so the TF varyings can be set). */
internal fun attachShaders(vertexSource: String, fragmentSource: String): Int {
	val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
	val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
	val program = GLES20.glCreateProgram()
	GLES20.glAttachShader(program, vertexShader)
	GLES20.glAttachShader(program, fragmentShader)
	return program
}

/** Compiles one shader stage, throwing with the info log if compilation fails. */
internal fun compileShader(stage: Int, source: String): Int {
	val shader = GLES20.glCreateShader(stage)
	GLES20.glShaderSource(shader, source)
	GLES20.glCompileShader(shader)
	val compiled = IntArray(1)
	GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
	check(compiled[0] != 0) { "GLES shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
	return shader
}