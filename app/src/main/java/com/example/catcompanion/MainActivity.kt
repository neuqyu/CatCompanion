package com.example.catcompanion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.FileOutputStream
import kotlin.math.max

class MainActivity : Activity() {
    companion object { private const val REQUEST_IMAGE = 1002 }

    private val background = Color.rgb(255, 248, 241)
    private val ink = Color.rgb(64, 51, 47)
    private val coral = Color.rgb(238, 135, 95)
    private var pendingStart = false
    private var importWithAi = true
    private var photoBusy = false
    private lateinit var photoStatus: TextView
    private lateinit var permissionSummary: TextView
    private lateinit var runningSummary: TextView
    private lateinit var photoPreview: ImageView
    private lateinit var sizeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        renderScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun panel(color: Int = Color.WHITE, radiusDp: Int = 22): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    private fun label(value: String, textSize: Float = 15f, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            setTextColor(ink)
            this.textSize = textSize
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(3).toFloat(), 1.08f)
        }

    private fun spacer(size: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(size))
    }

    private fun button(title: String, filled: Boolean = true, onTap: () -> Unit): Button =
        Button(this).apply {
            text = title
            isAllCaps = false
            textSize = 15f
            setTextColor(if (filled) Color.WHITE else ink)
            background = panel(if (filled) coral else Color.rgb(249, 232, 217), 14)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onTap() }
        }

    private fun LinearLayout.addSpace(size: Int) { addView(spacer(size)) }

    private fun LinearLayout.addFull(view: View) {
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun renderScreen() {
        
val scroll = ScrollView(this).apply {
    setBackgroundColor(this@MainActivity.background)
    setFillViewport(true)
}
  
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(23), dp(30), dp(23), dp(48))
        }
        scroll.addView(body)
        setContentView(scroll)

        body.addFull(label("喵在身边 🐾", 30f, true))
        body.addSpace(8)
        body.addFull(label("让你喜欢的小猫，陪你在安卓手机里散步。", 15f))
        body.addSpace(24)

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = panel(Color.rgb(255, 239, 220))
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        photoPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(126), dp(126))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewPanel.addView(photoPreview)
        previewPanel.addSpace(6)
        previewPanel.addView(label("你的小猫", 14f, true))
        body.addFull(previewPanel)
        body.addSpace(12)
        body.addFull(button("AI 识别并抠出猫咪（推荐）") { importWithAi = true; pickPhoto() })
        body.addSpace(8)
        body.addFull(button("直接导入原图", filled = false) { importWithAi = false; pickPhoto() })
        body.addSpace(7)
        photoStatus = label("AI 分割需 Google Play 服务；首次识别可能下载模型。", 12f)
        body.addFull(photoStatus)
        body.addSpace(8)
        body.addFull(button("恢复内置手绘小猫", filled = false) {
            PetSettings.petImage(this).delete()
            PetSettings.setAiCutout(this, false)
            photoStatus.text = "已恢复内置小猫"
            refreshPhotoPreview()
            refreshPet()
            Toast.makeText(this, "已切换回内置小猫", Toast.LENGTH_SHORT).show()
        })
        body.addSpace(6)
        body.addFull(label("请尽量使用单只猫的全身照片；复杂背景或多宠物可能抠错。", 12f))
        body.addSpace(25)

        body.addFull(label("显示模式", 19f, true))
        body.addSpace(10)
        val radio = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            background = panel()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val everywhere = RadioButton(this).apply { id = View.generateViewId(); text = "全局陪伴：其他 APP 中也显示"; textSize = 14f }
        val homeOnly = RadioButton(this).apply { id = View.generateViewId(); text = "仅桌面：打开其他 APP 后隐藏"; textSize = 14f }
        radio.addView(everywhere)
        radio.addView(homeOnly)
        radio.check(if (PetSettings.mode(this) == DisplayMode.HOME_ONLY) homeOnly.id else everywhere.id)
        radio.setOnCheckedChangeListener { _, checked ->
            PetSettings.setMode(this, if (checked == homeOnly.id) DisplayMode.HOME_ONLY else DisplayMode.EVERYWHERE)
            if (PetSettings.isRunning(this)) refreshPet()
            refreshStatus()
        }
        body.addFull(radio)
        body.addSpace(8)
        body.addFull(label("仅桌面模式使用“使用情况访问权限”判断当前 APP；全局模式无需该权限。", 12f))
        body.addSpace(22)

        body.addFull(label("猫咪尺寸", 19f, true))
        body.addSpace(6)
        sizeLabel = label("", 13f)
        body.addFull(sizeLabel)
        val seek = SeekBar(this).apply {
            max = 170
            progress = PetSettings.size(this@MainActivity) - 90
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    PetSettings.setSize(this@MainActivity, 90 + p)
                    sizeLabel.text = "${90 + p} dp（可随时调整）"
                    if (fromUser && PetSettings.isRunning(this@MainActivity)) refreshPet()
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        sizeLabel.text = "${PetSettings.size(this)} dp（可随时调整）"
        body.addFull(seek)
        body.addSpace(17)

        body.addFull(label("活动频率与安静模式", 19f, true))
        body.addSpace(6)
        val activityDescription = label("活动频率：${PetSettings.activity(this)}/5", 13f)
        body.addFull(activityDescription)
        val levelSeek = SeekBar(this).apply {
            max = 4
            progress = PetSettings.activity(this@MainActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    activityDescription.text = "活动频率：${p + 1}/5"
                    if (fromUser) {
                        PetSettings.setActivity(this@MainActivity, p + 1)
                        refreshPet()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }
        body.addFull(levelSeek)
        var autoButton: Button? = null
        fun autoLabel() = if (PetSettings.autoMotion(this)) "暂停自动动作" else "恢复自动动作"
        autoButton = button(autoLabel(), filled = false) {
            PetSettings.setAutoMotion(this, !PetSettings.autoMotion(this))
            autoButton?.text = autoLabel()
            refreshPet()
        }
        body.addFull(requireNotNull(autoButton))
        body.addSpace(8)
        body.addFull(button("小猫恢复默认位置", filled = false) {
            PetSettings.clearPosition(this)
            if (PetSettings.isRunning(this)) {
                startService(Intent(this, FloatingPetService::class.java)
                    .setAction(FloatingPetService.ACTION_RESET_POSITION))
            }
            Toast.makeText(this, "已重置小猫位置", Toast.LENGTH_SHORT).show()
        })
        body.addSpace(7)
        body.addFull(label("可拖动小猫；位置会被记住。暂停自动动作后仍可点击互动和手动触发动作。", 12f))
        body.addSpace(16)

        body.addFull(label("权限与运行", 19f, true))
        body.addSpace(8)
        permissionSummary = label("", 13f)
        body.addFull(permissionSummary)
        body.addSpace(10)
        body.addFull(button("① 授予悬浮窗权限", filled = false) { openOverlaySettings() })
        body.addSpace(8)
        body.addFull(button("② 仅桌面模式授权使用情况访问", filled = false) { openUsageSettings() })
        body.addSpace(18)

        body.addFull(button("开启猫咪悬浮宠物") { startRequested() })
        body.addSpace(8)
        body.addFull(button("关闭猫咪", filled = false) { stopPet() })
        body.addSpace(8)
        runningSummary = label("", 13f)
        body.addFull(runningSummary)
        body.addSpace(22)

        body.addFull(label("猫咪动作 · 手动测试", 18f, true))
        body.addSpace(8)
        for ((name, action) in listOf(
            "散步" to PetView.Action.WALK, "坐下" to PetView.Action.SIT,
            "睡觉" to PetView.Action.SLEEP, "伸展" to PetView.Action.STRETCH,
            "挥爪" to PetView.Action.WAVE, "跳跃" to PetView.Action.JUMP
        )) {
            body.addFull(button(name, filled = false) { playAction(action) })
            body.addSpace(5)
        }
        body.addSpace(8)
        body.addFull(label("V0.3：AI 主体识别、透明裁边、活动频率和安静模式。六种动作仍为二维模拟，并非 AI 生成真实四肢动画。", 13f))
        body.addSpace(8)
        body.addFull(label("隐私：照片仅保存在手机 APP 的私有目录。模型首次可能通过 Google Play 服务下载；照片保存在手机本地。", 12f))
        refreshPhotoPreview()
        refreshStatus()
    }

    private fun refreshPhotoPreview() {
        val bitmap = if (PetSettings.hasImage(this)) BitmapFactory.decodeFile(PetSettings.petImage(this).path) else null
        if (bitmap != null) photoPreview.setImageBitmap(bitmap)
        else photoPreview.setImageResource(R.drawable.ic_cat)
    }

    private fun refreshStatus() {
        if (!::permissionSummary.isInitialized) return
        val overlay = PermissionChecks.overlayAllowed(this)
        val usage = PermissionChecks.usageAllowed(this)
        permissionSummary.text = "悬浮窗：${if (overlay) "已授权 ✓" else "未授权"}    使用情况访问：${if (usage) "已授权 ✓" else "未授权"}"
        runningSummary.text = if (PetSettings.isRunning(this)) "运行状态：已开启，按 Home 键查看小猫。" else "运行状态：未开启。"
    }

    override fun onResume() {
        super.onResume()
        if (pendingStart && hasRequiredPermissions()) {
            pendingStart = false
            startPet()
        }
        if (::permissionSummary.isInitialized) refreshStatus()
    }

    private fun hasRequiredPermissions(): Boolean = PermissionChecks.overlayAllowed(this) &&
        (PetSettings.mode(this) == DisplayMode.EVERYWHERE || PermissionChecks.usageAllowed(this))

    private fun startRequested() {
        if (!hasRequiredPermissions()) {
            pendingStart = true
            if (!PermissionChecks.overlayAllowed(this)) {
                Toast.makeText(this, "请先授权悬浮窗，再返回应用", Toast.LENGTH_LONG).show()
                openOverlaySettings()
            } else {
                Toast.makeText(this, "仅桌面模式需要使用情况访问权限", Toast.LENGTH_LONG).show()
                openUsageSettings()
            }
            return
        }
        startPet()
    }

    private fun startPet() {
        val service = Intent(this, FloatingPetService::class.java).setAction(FloatingPetService.ACTION_START)
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
            Toast.makeText(this, "小猫已开启！按 Home 键看看吧", Toast.LENGTH_LONG).show()
            // The service sets the running flag asynchronously.
            window.decorView.postDelayed({ refreshStatus() }, 250L)
        } catch (e: Exception) {
            Toast.makeText(this, "启动失败：${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshPet() {
        if (!PetSettings.isRunning(this)) return
        try { startService(Intent(this, FloatingPetService::class.java).setAction(FloatingPetService.ACTION_REFRESH)) }
        catch (_: Exception) { /* system may have stopped service */ }
    }

    private fun stopPet() {
        pendingStart = false
        stopService(Intent(this, FloatingPetService::class.java))
        PetSettings.setRunning(this, false)
        refreshStatus()
    }

    private fun openOverlaySettings() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"))
        startActivity(intent)
    }

    private fun openUsageSettings() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun pickPhoto() {
        if (photoBusy) {
            Toast.makeText(this, "照片处理中，请勿重复选择", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_IMAGE)
    }

    @Deprecated("Use ActivityResult API when AndroidX is added")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (importWithAi) importAiPhoto(uri) else importOriginalPhoto(uri)
    }

    private fun importOriginalPhoto(uri: Uri) {
        try {
            saveScaledImage(uri)
            PetSettings.setAiCutout(this, false)
            photoStatus.text = "已导入原图（没有抠图）"
            refreshPhotoPreview()
            refreshPet()
        } catch (e: Exception) {
            photoStatus.text = "照片读取失败：" + e.localizedMessage
        }
    }

    private fun importAiPhoto(uri: Uri) {
        photoBusy = true
        photoStatus.text = "正在准备照片…"
        Thread {
            try {
                val original = CatPhotoProcessor.load(this, uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        photoBusy = false
                        original.recycle()
                        return@runOnUiThread
                    }
                    photoStatus.text = "AI 正在识别猫咪和提取前景…"
                    CatPhotoProcessor.cutout(original) { result ->
                        Thread {
                            val output = result.getOrNull()
                            val errorText = try {
                                if (output != null) {
                                    CatPhotoProcessor.save(this, output.bitmap)
                                    null
                                } else result.exceptionOrNull()?.localizedMessage ?: "模型没有返回结果"
                            } catch (error: Exception) {
                                error.localizedMessage ?: "保存失败"
                            } finally {
                                original.recycle()
                                output?.bitmap?.recycle()
                            }
                            runOnUiThread {
                                photoBusy = false
                                if (!isFinishing && !isDestroyed) {
                                    if (errorText == null) {
                                        PetSettings.setAiCutout(this, true)
                                        refreshPhotoPreview()
                                        refreshPet()
                                        val confidence = output?.catConfidence
                                        photoStatus.text = if (confidence != null) {
                                            "AI 抠图成功 · 猫咪识别置信度：${(confidence * 100).toInt()}%"
                                        } else {
                                            "已提取前景，但未确认识别为猫咪，请检查预览"
                                        }
                                    } else {
                                        photoStatus.text = "AI 处理失败：" + errorText + "；可尝试直接导入原图"
                                    }
                                }
                            }
                        }.start()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    photoBusy = false
                    if (!isFinishing && !isDestroyed) {
                        photoStatus.text = "读取图片失败：" + (error.localizedMessage ?: "未知原因")
                    }
                }
            }
        }.start()
    }

    private fun playAction(action: PetView.Action) {
        if (!PetSettings.isRunning(this)) {
            Toast.makeText(this, "请先开启悬浮猫咪", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startService(Intent(this, FloatingPetService::class.java)
                .setAction(FloatingPetService.ACTION_PLAY)
                .putExtra(FloatingPetService.EXTRA_BEHAVIOR, action.name))
        } catch (e: Exception) {
            Toast.makeText(this, "动作切换失败：" + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveScaledImage(uri: Uri) {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val ratio = minOf(1f, 640f / max(w, h).toFloat())
                decoder.setTargetSize(max(1, (w * ratio).toInt()), max(1, (h * ratio).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            var sample = 1
            while (max(options.outWidth, options.outHeight) / sample > 640) sample *= 2
            val readOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, readOptions) }
                ?: error("照片格式不支持")
        }
        try {
            CatPhotoProcessor.save(this, bitmap)
        } finally {
            bitmap.recycle()
        }
    }
}
