package com.example.inversealpha

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.*
import java.io.File

class MainActivity : ComponentActivity() {
    private var videoUri: Uri? = null
    private var bitmap: Bitmap? = null
    private var vw = 0
    private var vh = 0
    private var videoBitrate = 0
    private var transformer: Transformer? = null
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvVideo: TextView
    private lateinit var tvImage: TextView
    private lateinit var tvAlpha: TextView
    private lateinit var seek: SeekBar
    private lateinit var etX: EditText
    private lateinit var etY: EditText
    private lateinit var etW: EditText
    private lateinit var etH: EditText
    private lateinit var cbOrig: CheckBox
    private lateinit var cbVeryHigh: CheckBox
    private lateinit var bar: ProgressBar
    private lateinit var status: TextView
    private lateinit var imgPreview: ImageView

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onVideo(uri)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImage(uri)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        fun num(h: String, d: String = "0") = EditText(this).apply {
            hint = h; setText(d); inputType = InputType.TYPE_CLASS_NUMBER
        }

        root.addView(btn("選擇影片") { pickVideo.launch("video/*") })
        tvVideo = TextView(this).apply { text = "尚未選擇影片" }; root.addView(tvVideo)
        root.addView(btn("選擇圖片") { pickImage.launch("image/*") })
        tvImage = TextView(this).apply { text = "尚未選擇圖片" }; root.addView(tvImage)
        imgPreview = ImageView(this).apply {
            adjustViewBounds = true; maxHeight = dp(160)
        }; root.addView(imgPreview)

        tvAlpha = TextView(this).apply { text = "圖片不透明度：30%" }; root.addView(tvAlpha)
        seek = SeekBar(this).apply {
            max = 100; progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    tvAlpha.text = "圖片不透明度：$p%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }; root.addView(seek)

        root.addView(TextView(this).apply { text = "位置（左上角為 0,0）" })
        etX = num("X"); etY = num("Y"); root.addView(etX); root.addView(etY)
        cbOrig = CheckBox(this).apply { text = "自動使用圖片原始尺寸"; isChecked = true }
        root.addView(cbOrig)
        etW = num("圖片寬度", ""); etH = num("圖片高度", ""); root.addView(etW); root.addView(etH)
        cbVeryHigh = CheckBox(this).apply { text = "輸出品質：非常高（預設為高）" }
        root.addView(cbVeryHigh)
        root.addView(TextView(this).apply {
            text = "注意：僅當影片確實由 C=αI+(1-α)V 合成時才能正確還原；重新編碼無法保證回到未壓縮原始像素。"
            textSize = 12f
        })

        root.addView(btn("開始處理") { start() })
        root.addView(btn("取消") { cancel() })
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        status = TextView(this); root.addView(status)
    }

    private fun onVideo(uri: Uri) {
        try {
            val m = MediaMetadataRetriever()
            m.setDataSource(this, uri)
            var w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            var h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            val rot = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            videoBitrate = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            m.release()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            vw = w; vh = h; videoUri = uri
            tvVideo.text = "${nameOf(uri)}（${vw}×${vh}）"
        } catch (e: Exception) {
            videoUri = null
            tvVideo.text = "無法讀取影片：${e.message}"
        }
    }

    private fun onImage(uri: Uri) {
        try {
            val opt = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bm = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opt) }!!
            bitmap = bm
            tvImage.text = "${nameOf(uri)}（${bm.width}×${bm.height}）"
            imgPreview.setImageBitmap(bm)
        } catch (e: Exception) {
            bitmap = null
            tvImage.text = "無法讀取圖片：${e.message}"
        }
    }

    private fun nameOf(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use {
            val i = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && i >= 0) it.getString(i) else null
        } ?: (uri.lastPathSegment ?: "file")

    private fun start() {
        if (running) return
        val v = videoUri; val bm = bitmap
        if (v == null || bm == null) { toast("請先選擇影片與圖片"); return }
        val alpha = seek.progress / 100f
        if (alpha >= 1f) {
            toast("圖片完全不透明，原始影片資訊已被覆蓋，無法透過反向 Alpha 混合恢復。"); return
        }
        val x = etX.text.toString().toIntOrNull() ?: 0
        val y = etY.text.toString().toIntOrNull() ?: 0
        val w = if (cbOrig.isChecked) bm.width else (etW.text.toString().toIntOrNull() ?: 0)
        val h = if (cbOrig.isChecked) bm.height else (etH.text.toString().toIntOrNull() ?: 0)
        if (w <= 0 || h <= 0) { toast("圖片寬高必須大於 0"); return }
        if (x < 0 || y < 0 || x + w > vw || y + h > vh) {
            toast("圖片範圍超出影片（影片 ${vw}×${vh}），請調整 X/Y/寬/高"); return
        }

        val out = File(cacheDir, "restored.mp4").also { if (it.exists()) it.delete() }
        val base = if (videoBitrate > 0) videoBitrate else 8_000_000
        val bitrate = (base * (if (cbVeryHigh.isChecked) 2.5 else 1.5)).toInt()

        val effect = InverseAlphaEffect(bm, x, y, w, h, alpha)
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(v))
            .setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(effect)))
            .build()
        val enc = DefaultEncoderFactory.Builder(this)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
            .build()
        val t = Transformer.Builder(this)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(enc)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    running = false
                    bar.progress = 100
                    val saved = saveToGallery(out)
                    status.text = if (saved != null) "完成！已儲存到 Movies/InverseAlpha" else "完成，但儲存失敗"
                }
                override fun onError(composition: Composition, exportResult: ExportResult,
                                     exportException: ExportException) {
                    running = false
                    status.text = "失敗：${exportException.message}"
                }
            }).build()
        transformer = t
        running = true
        bar.progress = 0
        status.text = "處理中..."
        t.start(edited, out.absolutePath)
        poll(t)
    }

    private fun poll(t: Transformer) {
        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                if (!running) return
                if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                    bar.progress = holder.progress
                    status.text = "處理中... ${holder.progress}%"
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.post(r)
    }

    private fun cancel() {
        if (!running) return
        transformer?.cancel()
        running = false
        status.text = "已取消"
    }

    private fun saveToGallery(f: File): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "restored_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/InverseAlpha")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return uri
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        running = false
        transformer?.cancel()
        super.onDestroy()
    }
}
