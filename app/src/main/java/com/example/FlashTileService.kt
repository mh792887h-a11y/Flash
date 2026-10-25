package com.example

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.N)
class FlashTileService : TileService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(FlashManager.isFlashActive.value)
    }

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            FlashManager.isFlashActive.collectLatest { isActive ->
                updateTileState(isActive)
            }
        }
    }

    override fun onClick() {
        super.onClick()
        if (FlashManager.isFlashActive.value) {
            FlashService.stopService(this)
        } else {
            FlashService.startService(this)
        }
    }

    private fun updateTileState(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isActive) "مشتغل" else "مطفأ"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_flashlight_tile)
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
