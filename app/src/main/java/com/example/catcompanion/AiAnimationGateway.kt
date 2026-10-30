package com.example.catcompanion

import java.io.File

/**
 * Future extension point for AI generation. This is an interface, not a working AI service.
 * Implementations should validate responses and convert outputs into transparent animation frames.
 */
interface AiAnimationGateway {
    data class AnimationAsset(val action: String, val frames: List<File>, val framesPerSecond: Int)
    fun generateFromPhoto(inputPhoto: File, onComplete: (Result<List<AnimationAsset>>) -> Unit)
}

/** Called explicitly by future AI UI. Current V0.1 never claims it generated animations. */
class UnconfiguredAiAnimationGateway : AiAnimationGateway {
    override fun generateFromPhoto(
        inputPhoto: File,
        onComplete: (Result<List<AiAnimationGateway.AnimationAsset>>) -> Unit
    ) = onComplete(Result.failure(UnsupportedOperationException("AI service is not yet configured")))
}
