package io.github.dgproman.pihome.connect

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.dgproman.pihome.hub.HubAddress
import io.github.dgproman.pihome.hub.reachesLocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the app may reach a hub on the network the phone is on.
 *
 * From Android 17 that takes a permission, and a connection made without it
 * does not fail: it hangs until it runs out of time, which would read as a hub
 * that is switched off. So the app asks this first, and asks the person for
 * the permission when it is needed and missing.
 */
interface LocalNetwork {
    /** True when reaching [address] needs the permission, and it has not been given. */
    suspend fun mustAsk(address: HubAddress): Boolean
}

/**
 * The permission, as Android 17 names it. Spelled out rather than read from
 * `Manifest.permission`, where it is a constant older releases do not have.
 */
const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

class AndroidLocalNetwork(
    private val context: Context,
) : LocalNetwork {
    override suspend fun mustAsk(address: HubAddress): Boolean {
        // Earlier Android lets every app reach the local network.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return false
        if (context.checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) return false
        return withContext(Dispatchers.IO) { address.reachesLocalNetwork() }
    }
}
