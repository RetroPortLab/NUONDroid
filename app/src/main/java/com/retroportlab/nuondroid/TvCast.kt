package com.retroportlab.nuondroid

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRouter
import android.provider.Settings
import android.widget.Toast

/**
 * Cast to TV: Android's own screen casting (Chromecast, Google TV, and TVs with
 * Chromecast built in). It mirrors the whole screen, sound included, so the game
 * shows on the TV and the handheld at the same time. NUONDroid opens the system's
 * cast picker and shows where it's casting; the system does the streaming.
 */
object TvCast {
    /** Name of the TV being cast to, or null when not casting. */
    fun castingTo(ctx: Context): String? {
        val router = ctx.getSystemService(Context.MEDIA_ROUTER_SERVICE) as? MediaRouter ?: return null
        val route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO) ?: return null
        if (route == router.defaultRoute) return null
        return route.getName(ctx)?.toString()?.takeIf { it.isNotBlank() } ?: "TV"
    }

    fun status(ctx: Context): String = castingTo(ctx)?.let { "Casting to $it" } ?: "Not casting"

    /** Opens the system's cast picker. Returns false if this device has none. */
    fun openPicker(activity: Activity): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS"),
            Intent("com.android.settings.CAST_SETTINGS"),
        )
        for (i in intents) {
            try {
                activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Toast.makeText(activity, "Pick your Chromecast or Google TV, then come back with Back", Toast.LENGTH_LONG).show()
                return true
            } catch (e: ActivityNotFoundException) {
            } catch (e: SecurityException) {
            }
        }
        Toast.makeText(activity, "No cast menu found. Swipe down from the top and use the Cast or Screen Cast tile.", Toast.LENGTH_LONG).show()
        return false
    }
}
