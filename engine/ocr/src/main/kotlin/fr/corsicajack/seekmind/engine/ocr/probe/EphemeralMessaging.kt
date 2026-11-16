package fr.corsicajack.seekmind.engine.ocr.probe

/**
 * Messaging apps whose secure flag varies from one screen to the next (disappearing messages, view-once
 * media): probed at every capture on API 30-33, like the browsers (docs/specs/ocr.md §3.2). Their
 * presence here only makes the probe more frequent; it never lets a capture through.
 */
object EphemeralMessaging {
    val PACKAGES: Set<String> = setOf(
        "org.thoughtcrime.securesms", // Signal
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.snapchat.android",
        "com.facebook.orca", // Messenger
        "com.instagram.android",
        "ch.threema.app",
        "ch.threema.app.libre",
        "com.wire",
        "network.loki.messenger", // Session
        "im.vector.app", // Element
        "chat.simplex.app",
        "com.discord",
    )

    /** [OcrConfig.probeAlwaysPackages][fr.corsicajack.seekmind.core.model.config.OcrConfig]: browsers and these apps. */
    fun probeAlways(browsers: Set<String>): Set<String> = browsers + PACKAGES
}
