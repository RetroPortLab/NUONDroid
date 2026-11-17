package com.retroportlab.nuondroid

import android.content.Context
import java.io.File

/**
 * The core looks for bios.cof / minibios.cof / minibiosX.cof and nuance.cfg in
 * "<system dir>/nuance" (see retro_load_game in upstream libretro.cpp). The .cof
 * files ship inside the APK (copied from the upstream checkout at build time).
 */
object BiosInstaller {
    /** Returns the libretro "system directory". */
    fun install(ctx: Context, coreCrt: Boolean, blockMode: Boolean): File {
        val system = File(ctx.filesDir, "system")
        val dir = File(system, "nuance").apply { mkdirs() }

        for (name in ctx.assets.list("nuance").orEmpty()) {
            val out = File(dir, name)
            ctx.assets.open("nuance/$name").use { input ->
                val bytes = input.readBytes()
                if (!out.exists() || out.length() != bytes.size.toLong()) out.writeBytes(bytes)
            }
        }

        // Rewritten every launch so the settings screen is the single source of truth.
        // DynamicCompiler on ARM means the IL block mode (there is no ARM JIT).
        // Before patches/0006 that mode stalled the NUON (black screen, no sound).
        File(dir, "nuance.cfg").writeText(
            """
            [AudioInterrupts]
            Enabled

            [DynamicCompiler]
            ${if (blockMode) "Enabled" else "Disabled"}

            [CompilerConstantPropagation]
            Enabled

            [CompilerDeadCodeElimination]
            Disabled

            [DumpCompiledBlocks]
            Disabled

            [MPE3PacketHack]
            Enabled

            [AutomaticLoadPopup]
            Disabled

            [UseCRTshader]
            ${if (coreCrt) "Enabled" else "Disabled"}
            """.trimIndent() + "\n"
        )
        return system
    }
}
