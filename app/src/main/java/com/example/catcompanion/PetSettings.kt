package com.example.catcompanion

import android.content.Context
import java.io.File

internal enum class DisplayMode { EVERYWHERE, HOME_ONLY }

internal object PetSettings {
    private const val PREFS = "cat_companion_prefs"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): DisplayMode =
        if (prefs(context).getString("mode", "EVERYWHERE") == "HOME_ONLY") DisplayMode.HOME_ONLY else DisplayMode.EVERYWHERE

    fun setMode(context: Context, value: DisplayMode) {
        prefs(context).edit().putString("mode", value.name).apply()
    }

    fun size(context: Context) = prefs(context).getInt("size", 155).coerceIn(90, 260)
    fun setSize(context: Context, value: Int) {
        prefs(context).edit().putInt("size", value.coerceIn(90, 260)).apply()
    }

    fun isRunning(context: Context) = prefs(context).getBoolean("running", false)
    fun setRunning(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("running", value).apply()
    }

    fun wasAiCutout(context: Context): Boolean = prefs(context).getBoolean("was_ai_cutout", false)
    fun setAiCutout(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("was_ai_cutout", value).apply()
    }

    fun autoMotion(context: Context): Boolean = prefs(context).getBoolean("auto_motion", true)
    fun setAutoMotion(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("auto_motion", enabled).apply()
    }

    fun activity(context: Context): Int = prefs(context).getInt("activity", 3).coerceIn(1, 5)
    fun setActivity(context: Context, level: Int) {
        prefs(context).edit().putInt("activity", level.coerceIn(1, 5)).apply()
    }

    fun savedPosition(context: Context): Pair<Float, Float>? {
        val prefs = prefs(context)
        return if (prefs.contains("position_x") && prefs.contains("position_y"))
            Pair(prefs.getFloat("position_x", .5f).coerceIn(0f, 1f),
                 prefs.getFloat("position_y", .85f).coerceIn(0f, 1f))
        else null
    }

    fun setPosition(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat("position_x", x.coerceIn(0f, 1f))
            .putFloat("position_y", y.coerceIn(0f, 1f)).apply()
    }

    fun clearPosition(context: Context) {
        prefs(context).edit().remove("position_x").remove("position_y").apply()
    }

    fun petImage(context: Context) = File(context.filesDir, "custom_pet.png")
    fun hasImage(context: Context) = petImage(context).isFile
}
