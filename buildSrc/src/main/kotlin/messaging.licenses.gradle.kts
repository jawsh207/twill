import messaging.licenses.CopyrightOverride
import messaging.licenses.ExtraNotice
import messaging.licenses.GenerateLicensesTask

tasks.register<GenerateLicensesTask>("generateLicenses") {
    group = "documentation"
    description = "Generates assets/licenses.html from the release runtime classpath."

    output.set(rootProject.file("assets/licenses.html"))

    copyrightOverrides.add(
        CopyrightOverride(
            coordinates = "com.github.bumptech.glide:*",
            copyright = "Copyright 2014 Google, Inc. All rights reserved.",
        ),
    )
    // Twill: Nostr support (Quartz, the protocol library behind Amethyst).
    listOf("com.vitorpamplona.quartz:*", "com.vitorpamplona.negentropy:*").forEach { group ->
        copyrightOverrides.add(
            CopyrightOverride(
                coordinates = group,
                copyright = "Copyright (c) 2025 Vitor Pamplona",
            ),
        )
    }

    extraNotices.addAll(
        ExtraNotice(
            name = "AOSP",
            spdxId = "Apache-2.0",
            text = "Copyright (c) 2005-2008, The Android Open Source Project",
        ),
        // Twill: the Delta Chat core (chatmail core), bundled as a program; see
        // scripts/fetch-deltachat-core.py. Its source code is at the URL below.
        ExtraNotice(
            name = "Delta Chat core (chatmail core) 2.63.0",
            url = "https://github.com/chatmail/core",
            spdxId = "MPL-2.0",
            text = "Copyright (c) 2019 by Bjoern Petersen and contributors",
        ),
        ExtraNotice(
            name = "GIFLIB",
            spdxId = "MIT",
            text = "The GIFLIB distribution is Copyright (c) 1997  Eric S. Raymond",
        ),
    )
}
