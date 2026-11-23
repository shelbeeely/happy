# Mobile coding companion: OpenRouter, on-device AI and Android integration

What the Android app adds for driving coding sessions from a phone, how each
piece works, what was verified where, and what still needs a physical Pixel.
Host side: `docs/openrouter.md` at the repository root.

## Features

### OpenRouter profiles (Settings › AI providers)

- **Profiles** (`inference/InferenceProfile.kt`): name, OpenRouter model slug,
  optional background model, routing (`:nitro` / `:floor`), server tools (web
  search, web fetch, date and time), warning threshold and host-enforced hard
  limit, and a **credential reference** (`happy:openrouter` or `env:NAME`).
  They sync in the account's encrypted settings under `inferenceProfiles`,
  parsed leniently: a bad entry is dropped, never a reason to reset settings.
- **Model browser** (`ui/inference/ProfileEditor.kt` `ModelBrowserScreen`): OpenRouter's
  public `/api/v1/models`, cached on the phone (`OpenRouterCatalog`): search,
  favourites, context length, input modalities, tool calling, reasoning,
  published price. "Tool calling only" is on by default, since Claude Code needs it.
- **Computers**: each Happy CLI computer's capability (it must advertise
  `machineMetadata.inference`), which credential references it can resolve,
  and OpenRouter Ori if installed. **Store key on this computer** sends the key
  once over the end-to-end encrypted machine RPC; the phone keeps no copy.
  So nothing needs Keystore storage on the phone.
- **Test connection** runs on the chosen computer (key, model, server tools)
  and words each failure: missing credential, rejected key, unknown model,
  model without tool calling, rejected server tool, OpenRouter unreachable from
  the computer, computer not answering, or Happy CLI too old.

### Starting and running a session

- The new-session **model picker** lists OpenRouter profiles under "OpenRouter"
  for Claude Code. They are disabled, with the reason, on computers whose Happy
  CLI can't apply them. The start sends `inference` with the spawn
  (`SessionStarter`, `MachineOps.spawnRequest`). The message model chip then
  carries the slug.
- **Session companion bar** (`ui/localai/SessionCompanion.kt`, over the composer):
  - Provider line: harness, provider and model **as the computer
    acknowledged them** (`InferenceAck`): "confirmed by computer",
    "waiting for the computer", or a mismatch warning. Tapping it opens the details.
  - Spend: tokens and cost with their source: "reported" (OpenRouter's own
    figure), "~ estimated", or "cost not reported". Sessions without a profile
    show the last reply's usage, labelled estimated (Happy CLI prices it from
    Anthropic list prices).
  - Budget banner at the warning threshold, at 80% of the hard limit, and when
    the computer stops new requests, each with one subtle haptic
    (`HapticFeedbackConstants`, which follows the system's haptics setting).
- **Model changes**: the chip lists OpenRouter slugs for an OpenRouter session.
  A change applies from the next message in the same conversation. Another
  provider needs a new session, and the details sheet says so.
- Permissions, streaming, stop and reconnection are unchanged: permission
  requests stay explicit buttons on the tool row, separate from chat.

### On-device AI (Gemini Nano through AICore)

`localai/` uses the ML Kit GenAI **Prompt API** (`com.google.mlkit:genai-prompt:1.0.0-beta5`).

- **Availability** comes from `checkStatus()` at runtime (Unavailable /
  Downloadable / Downloading / Available), never from a device list. The model
  download is started and cancelled from Settings › AI providers › On-device
  AI or from the first use, with progress.
- **Foreground only**: requests run only while the app is in the foreground
  (`AppGraph.isForeground`), are refused otherwise, and are cancelled when the
  app leaves. There is no foreground service: AICore refuses those too.
- **Errors**: BUSY is retried with backoff (3 tries, or AICore's retry delay).
  Battery quota, background block, too large, storage, system update, bad image
  and network each have their own words.
- **Bounded input**: the model's token limit (`getTokenLimit`, 4000 if unknown)
  minus an output reserve. Long sessions are summarised in up to 6 slices
  (newest kept), then the slice summaries are combined.
- **Untrusted content**: session text, tool output, logs and screenshots go
  inside a `<session-data>` fence whose closing tag can't be forged, under fixed
  instructions to treat it as data. The model has no tools.
- **Drafts only**: every result opens in a sheet. Only Accept / Add to draft
  puts it in the composer, and only Send sends it.
- **No cloud fallback** exists, so nothing can be uploaded by accident. The bar
  says "On device".

Features:
- **Catch me up**: what happened, files changed, unresolved errors and pending
  approvals, and the next useful instruction, from the chat already on the phone
  (`SessionDigest`).
- **Improve**: rewrites the composer's draft, with the original shown beside
  it: Accept, Edit, Cancel.
- **Explain**: the last error, the last tool output, the last reply, or copied
  text. Also **Explain with Happy** in the system text-selection menu, in
  Happy's own selectable text and in other apps (`ExplainTextActivity`,
  `PROCESS_TEXT`, read-only).
- **Screenshot**: the Photo Picker (no storage permission), downscaled to
  1024 px, drafts a bug report or a UI change request with an optional note.

### Voice, sharing, shortcuts, assistants

- **Push-to-talk** (`localai/Dictation.kt`): hold the mic chip, release to
  transcribe into the draft for editing. It uses only the **on-device**
  recognizer (`createOnDeviceSpeechRecognizer`, Android 12+). Where there is
  none, dictation is unavailable rather than quietly online. Language
  support and model download use `checkRecognitionSupport` /
  `triggerModelDownload` (Android 13+). The microphone permission is asked on
  first use. TalkBack users double-tap to start and again to stop.
- **Share target** (`ShareTargetActivity`): text and up to 4 pictures from any
  app go to a session the person picks (or a new task's prompt, for text). It
  lands in that session's draft through `CompanionInbox` (no-backup storage,
  survives process death). Pictures are copied while the share's grant lasts.
- **Launcher shortcuts**: "New task" (static) and the three most recently
  opened sessions (dynamic). Signing out removes them.
- **AppFunctions** (Android 16+, `androidx.appfunctions:appfunctions:1.0.0`, KSP
  `2.3.14`): `listRecentSessions`, `openSession` and `draftTask`.
  `draftTask` only puts a draft in a session's composer (or a new task) and
  returns an intent that opens it. The chat then says an assistant drafted it.
  No function sends a message or answers a permission. Only system assistants
  holding `EXECUTE_APP_FUNCTIONS` can call them.
  - **Gemini** calling them requires enrolling in Google's **Connected Apps
    Platform developer preview** (connected.google.com/console), creating an
    Android MCP connected app in Google Connected Console, and uploading a
    bundle to a testing track with the same testers on both. Until published,
    only listed testers on that bundle version can use it.
  - Local check: `adb shell cmd app_function list-app-functions --package com.slopus.happy.dev`.

## Privacy and reliability

- No new code logs keys, session text, prompts, model output or screenshots.
  The CLI's spawn log now redacts the token and environment values.
- Keys never touch the phone's storage. Profiles hold references only.
- The composer's draft (and anything the inbox adds) survives recreation and
  process death through the existing composer mirror. Sending still goes
  through `MessageSender`'s outbox, whose local ids keep retries from
  duplicating messages, and a second Send while one is in flight is ignored.
- Design follows the app's own design system (happy-app tokens, AGENTS.md
  rule 1) rather than Material dynamic color. It keeps dark mode, edge-to-edge,
  TalkBack labels, large text and the tablet shell. The companion's actions sit
  directly over the composer for one-handed reach.

## Build and test (Linux container)

```bash
# SDK (once): cmdline-tools, then
sdkmanager "platforms;android-37.0" "build-tools;36.0.0" "platform-tools"
echo "sdk.dir=/opt/android-sdk" > local.properties
./gradlew :app:assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest --tests 'com.slopus.happy.inference.*' \
  --tests 'com.slopus.happy.localai.*' --tests 'com.slopus.happy.ui.inference.*' \
  --tests 'com.slopus.happy.text.*'
```

## Physical device checklist (Pixel 10 or newer)

None of these could run in the container. Check each on a device:

1. **AICore**: Settings › AI providers › On-device AI shows Ready or Ready to
   download. Download shows progress, and Cancel works. Then the state is Ready.
2. **Foreground rule**: start Catch me up, press Home: the sheet reports "only
   runs while Happy is open" and nothing keeps running.
3. **BUSY / quota**: run Improve repeatedly. A busy model retries quietly; a
   quota message appears only after AICore refuses.
4. **Catch me up** on a long session (hundreds of messages) finishes within the
   window (sliced). Check that file paths and failing commands are kept.
5. **Improve**: Accept replaces the draft, Edit lets you change it, Cancel
   leaves the draft as it was. Nothing is sent.
6. **Explain**: select text in a lifted message: "Explain with Happy" appears in
   the selection menu. Also try it from another app.
7. **Screenshot**: pick a screenshot, choose Bug report, add a note. The draft
   mentions what the image shows. An image-less device reports an error, not a crash.
8. **Dictation**: hold the mic. The partial text shows, release fills the draft.
   Deny the microphone once and check the message. Switch the system language to
   one without an on-device model and check the download offer.
9. **Share**: share text from Chrome and a screenshot from Photos into a session.
   Kill Happy from Recents before picking: the draft still arrives.
10. **Shortcuts**: long-press the icon: New task and recent sessions open the
    right screens.
11. **AppFunctions** (Android 16): `adb shell cmd app_function list-app-functions
    --package <id>` lists three functions, and `execute-app-function … draftTask`
    opens the session with the draft and the assistant notice.
12. **TalkBack and large text**: the companion bar, sheets and settings read in
    order and stay usable at the largest font size. The mic works with double-tap.
13. **Landscape, foldable and tablet**: the companion bar stays over the composer
    in the one-row and two-pane layouts.
14. **OpenRouter end to end** (needs a valid OpenRouter key on the computer):
    store the key, test the connection, start a session with a profile.
    The provider line turns "confirmed by computer" with the model Claude Code
    reports, and spend appears as "reported". Set a hard limit of $0.05: after
    it is reached, the computer refuses the next turn and the banner says so.
