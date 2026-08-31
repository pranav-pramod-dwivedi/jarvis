package com.pr4nav.jarvis

import android.app.Application
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TermuxBridge.init(this)
        Fs.init(this)
        Capabilities.init(this)
        Thread { RootCapability.detect() }.start()
        com.pr4nav.jarvis.needle.NeedleRuntime.init(this)
    }
}
