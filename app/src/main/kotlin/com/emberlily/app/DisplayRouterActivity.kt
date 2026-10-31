package com.emberlily.app

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Display

/**
 * A short-lived launcher in its own task. It can finish itself after routing the
 * singleTask player to the default display without finishing the player task.
 * It never owns a PlayerModel, emulator core, save file or Presentation.
 */
class DisplayRouterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeToPlayer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeToPlayer()
    }

    private fun routeToPlayer() {
        val player = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_DISPLAY_ROUTED, true)
        try {
            startActivity(player, ActivityOptions.makeBasic()
                .setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle())
        } catch (failure: Exception) {
            Log.w(TAG, "Default-display launch refused; falling back to a normal player launch", failure)
            // The marker prevents the fallback from bouncing back through this router.
            startActivity(player)
        } finally {
            finish()
        }
    }

    companion object {
        const val EXTRA_DISPLAY_ROUTED = "com.emberlily.app.DISPLAY_ROUTED"
        private const val TAG = "EmberLilyDisplays"
    }
}
