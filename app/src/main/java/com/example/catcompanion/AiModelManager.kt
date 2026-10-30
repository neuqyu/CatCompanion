package com.example.catcompanion

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.util.concurrent.atomic.AtomicBoolean

/** Google ML Kit's subject model must be separately installed by Play services. */
internal object AiModelManager {
    fun ensureAvailable(
        context: Context,
        progress: (String) -> Unit,
        done: (Result<Unit>) -> Unit
    ) {
        val state = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (state != ConnectionResult.SUCCESS) {
            done(Result.failure(IllegalStateException(
                "Google Play 服务不可用（状态码 $state）。此手机可使用离线纯色背景去除，或安装可正常工作的 Google Play 服务。"
            )))
            return
        }
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        val client = ModuleInstall.getClient(context)
        val finished = AtomicBoolean(false)
        var listener: InstallStatusListener? = null
        fun finish(result: Result<Unit>) {
            if (!finished.compareAndSet(false, true)) return
            segmenter.close()
            listener?.let { client.unregisterListener(it) }
            done(result)
        }
        try {
            progress("检查 AI 抠图模型是否已安装…")
            client.areModulesAvailable(segmenter)
                .addOnSuccessListener { available ->
                    if (available.areModulesAvailable()) {
                        progress("AI 模型已就绪")
                        finish(Result.success(Unit))
                    } else {
                        progress("AI 模型尚未安装，正在通过 Google Play 服务请求下载…")
                        listener = InstallStatusListener { status ->
                            when (status.installState) {
                                ModuleInstallStatusUpdate.InstallState.STATE_DOWNLOADING -> {
                                    progress("AI 模型下载中，请保持网络连接…")
                                }
                                ModuleInstallStatusUpdate.InstallState.STATE_INSTALLING -> {
                                    progress("AI 模型安装中…")
                                }
                                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                    progress("AI 模型安装完成")
                                    finish(Result.success(Unit))
                                }
                                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                                    finish(Result.failure(IllegalStateException(
                                        "Google AI 模型下载失败（错误码 ${status.errorCode}），请检查 Google Play 服务和网络。"
                                    )))
                                }
                            }
                        }
                        val request = ModuleInstallRequest.newBuilder()
                            .addApi(segmenter)
                            .setListener(requireNotNull(listener))
                            .build()
                        client.installModules(request)
                            .addOnSuccessListener { response ->
                                if (response.areModulesAlreadyInstalled()) {
                                    finish(Result.success(Unit))
                                } else {
                                    progress("下载请求已发送，模型下载和安装完成后自动继续…")
                                }
                            }
                            .addOnFailureListener { error ->
                                finish(Result.failure(IllegalStateException(
                                    "无法安装 AI 模型：${error.localizedMessage}。可使用离线纯色背景去除。", error
                                )))
                            }
                    }
                }.addOnFailureListener { error ->
                    finish(Result.failure(IllegalStateException(
                        "无法检查 AI 模型：${error.localizedMessage}。可使用离线纯色背景去除。", error
                    )))
                }
        } catch (error: Exception) {
            finish(Result.failure(error))
        }
    }
}
