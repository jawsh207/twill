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
    extraNotices.addAll(
        ExtraNotice(
            name = "AOSP",
            spdxId = "Apache-2.0",
            text = "Copyright (c) 2005-2008, The Android Open Source Project",
        ),
        // Twill: the Delta Chat core (chatmail core), built from source by
        // scripts/build-deltachat-core.sh. Its source code is at the URL below.
        ExtraNotice(
            name = "Delta Chat core (chatmail core) 2.63.0",
            url = "https://github.com/chatmail/core",
            spdxId = "MPL-2.0",
            text = "Copyright (c) 2019 by Bjoern Petersen and contributors",
        ),
        // Twill: Google Sans Flex (res/font/google_sans_flex.ttf), the app's font.
        ExtraNotice(
            name = "Google Sans Flex",
            url = "https://github.com/googlefonts/googlesans-flex",
            text = rootProject.file("assets/licenses/google_sans_flex-OFL.txt").readText(),
        ),
        ExtraNotice(
            name = "GIFLIB",
            spdxId = "MIT",
            text = "The GIFLIB distribution is Copyright (c) 1997  Eric S. Raymond",
        ),
    )
}
