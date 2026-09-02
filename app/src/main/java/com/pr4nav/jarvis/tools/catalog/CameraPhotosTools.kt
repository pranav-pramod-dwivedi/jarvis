package com.pr4nav.jarvis.tools.catalog

import android.content.Intent
import android.provider.MediaStore
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema

object CameraPhotosTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {
        reg(CanonicalToolDef(
            name = "camera_photo_take",
            description = "Opens camera ready to capture a photo.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📷 Opening Camera.")
            }
        ))

        reg(CanonicalToolDef(
            name = "camera_video_record",
            description = "Opens camera ready to record video.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("📹 Opening Video Recorder.")
            }
        ))

        reg(CanonicalToolDef(
            name = "photos_open_app",
            description = "Opens Photos or Gallery app.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                JarvisIntentRouter.routeAndExecute(ctx, "Open Photos") {}
                ok("▶️ Opening Photos.")
            }
        ))
    }
}
