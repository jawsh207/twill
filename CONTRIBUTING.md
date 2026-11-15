# Contributing to Twill

Twill is a fork of GrapheneOS Messaging. Upstream code keeps its own style; keep changes to
it small so upstream merges stay easy, and put Twill's code in new files where possible.

## Describe the code as you write it

Documentation is part of every change, not a follow-up.

1. **Every class, object, interface and top-level function in Twill's own code has a short
   `/** … */` description** right above it (after any annotations): in plain words, what it
   is and why it exists, and anything surprising, such as an Android rule it works around.
   Twill's own code is the `twill`, `nostr` and `deltachat` packages, their screens under
   `ui/`, and any file whose name starts with `Twill`. When you change something, re-read
   its description and fix anything the change made wrong.
2. **Comments explain why, not what.** Android behaviours, protocol rules, choices between
   alternatives. Don't narrate the code line by line.
3. **Only describe what's there.** Check each claim against the code.
4. **Keep the docs in step, in the same change:**
   - `NOTES.md` when a feature, screen, setting or file is added, removed, renamed or moved;
   - `README.md` for what users see: features, permissions, installing;
   - `CHANGELOG.md` for anything users will notice, under the next version.

CI enforces the first rule: `python3 .github/ci/check-docs.py` lists anything undescribed
and fails the build. Run it before pushing.

## Style and checks

- Kotlin follows ktlint (`.editorconfig`, 100 columns). CI runs `ktlintCheck` and detekt.
- New strings go in `res/values/strings.xml`.
- Options that contact a third party default to off.
- Twill's code is Apache 2.0. Don't add GPL code or artwork.
