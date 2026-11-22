package com.universalremote.tv

import android.app.Application
import com.universalremote.tv.remote.RemoteRepository

/** Holds the process-wide repository so every screen shares one TV connection. */
class UniversalRemoteApp : Application() {

    val repository: RemoteRepository by lazy { RemoteRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Touch the repository early so the client identity is created before the first pairing.
        repository
    }
}
