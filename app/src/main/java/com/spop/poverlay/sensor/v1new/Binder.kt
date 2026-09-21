package com.spop.poverlay.sensor.v1new

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.IOException

const val SERVICE_ACTION = "com.onepeloton.affernetservice.IV1Interface"

/** Own the binding for the collection lifetime, including cancellation and service death. */
internal fun v1Bindings(context: Context) = callbackFlow<IBinder> {
    val deadline = launch {
        delay(30_000)
        close(IOException("Peloton sensor binding timed out"))
    }
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) close(IOException("Peloton sensor service returned no binder"))
            else { deadline.cancel(); trySend(binder) }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            close(IOException("Peloton sensor service disconnected"))
        }
        override fun onBindingDied(name: ComponentName?) {
            close(IOException("Peloton sensor binding died"))
        }
        override fun onNullBinding(name: ComponentName?) {
            close(IOException("Peloton sensor service returned a null binding"))
        }
    }
    val intent = Intent(SERVICE_ACTION).setPackage("com.onepeloton.affernetservice")
    try {
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            close(IOException("Cannot bind Peloton sensor service"))
        }
        awaitClose { }
    } finally {
        deadline.cancel()
        runCatching { context.unbindService(connection) }
    }
}
