package org.umamo.editor.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.umamo.render.gles.GlesProbe
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Runs the on-device GLES self-check and shows what it found.
 *
 * This is its own launcher activity, deliberately NOT part of the editor shell: the editor's viewport is
 * not wired to the GLES device yet, and the shell has its own startup path to prove. Keeping the probe
 * separate means the renderer port can be validated on a device even while either of those is broken -
 * which is exactly the situation it was written for.
 *
 * It has no Compose dependency and touches nothing outside `android.*` plus `GlesProbe`, so it stays
 * buildable even if the shared UI is mid-change.
 *
 * The report is shown, logged, and copied to the clipboard (so it can be handed back without a screenshot).
 * Android GLES 3.0 自检。エディタ本体とは別のランチャーとして動く。
 */
class ProbeActivity : Activity() {
	private lateinit var statusView: TextView
	private lateinit var imageView: ImageView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics).toInt()
		val layout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, padding, padding, padding)
		}
		val heading = TextView(this).apply {
			text = "GLES 自检（Umamo Android 渲染端口）"
			textSize = 18f
		}
		statusView = TextView(this).apply {
			text = "运行中…"
			textSize = 12f
			typeface = android.graphics.Typeface.MONOSPACE
			setTextIsSelectable(true)
		}
		imageView = ImageView(this).apply {
			adjustViewBounds = true
		}
		val scroller = ScrollView(this).apply { addView(statusView) }
		layout.addView(heading)
		layout.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
		layout.addView(imageView)
		setContentView(layout)

		thread(name = "gles-probe") {
			// The probe needs a current context on THIS thread: the device is single-threaded by contract,
			// and GlesProbe creates and owns the EGL context itself.
			val result = try {
				GlesProbe().run()
			} catch (failure: Throwable) {
				// GlesProbe already catches Throwable internally; this is belt-and-braces so a failure in
				// reporting can never take the app down with it.
				Log.e(TAG, "probe crashed", failure)
				null
			}
			// A recorded crash is the more urgent finding, so it leads the report: if the editor shell
			// died at startup, that is what the reader needs first, not the renderer's verdict.
			val crash = CrashLog.read(this)
			val report = buildString {
				if (crash != null) {
					append("=== 编辑器上次崩溃 ===\n").append(crash).append('\n')
					append("=== 渲染自检 ===\n")
				}
				append(result?.report ?: "自检未能运行（见日志）")
			}
			val bitmap = result?.pixels?.let { toBitmap(it, result.width, result.height) }
			Log.i(TAG, "\n$report")
			copyToClipboard(report)
			runOnUiThread {
				statusView.text = report
				if (bitmap != null) {
					imageView.setImageBitmap(Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, false))
				}
			}
		}
	}

	/**
	 * Turns the probe's RGBA8888 rows into a bitmap. Android's ARGB_8888 bitmaps hold their bytes in R, G,
	 * B, A order in memory, so the buffer copies straight across with no per-pixel work.
	 */
	private fun toBitmap(rgba: ByteArray, width: Int, height: Int): Bitmap? {
		if (width <= 0 || height <= 0 || rgba.size < width * height * 4) return null
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
		return bitmap
	}

	private fun copyToClipboard(text: String) {
		val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
		clipboard.setPrimaryClip(ClipData.newPlainText("Umamo GLES probe", text))
	}

	private companion object {
		const val TAG = "UmamoProbe"
	}
}