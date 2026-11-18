# DustIt

A cleanup-first photo app. An on-device classifier and a few simple rules sort your library into cleanup suggestions. You review each one in bulk or by swiping, and send what you don't need to the trash.

It can also clean the WhatsApp folders that no gallery can see (see below).

Everything runs on the device. The app does not request the `INTERNET` permission.

## Suggestions

| Group | Source | Pre-selected |
|---|---|---|
| Chat screenshots, Greetings & forwards, Memes, Notices, Documents, Blurry photos | Classifier | Score ≥ 0.9; 0.6–0.9 shown as "worth a look" |
| Similar photos | Byte-identical copies (any content), plus near-identical **photo-like** shots: duplicates anywhere, bursts within 60 s | Everything but the best of each set (largest, then sharpest) |
| Screenshots | Files in any `*Screenshot*` folder | Older than 30 days |
| Large videos | Videos ≥ 50 MB | Never |

| WhatsApp: Sent from this phone | WhatsApp's copy of everything you send | Sent photos whose original is still in the gallery (it's the kept photo of their similar set) |
| WhatsApp: Chats kept out of gallery, Video messages, Stickers | Those hidden folders | Never |

All of this lives in `cleanup/CleanupRules.kt`, the single source of truth for the home cards, review screens and reminders.

Text-heavy images (chats, documents, notices, memes, forwards, screenshots) are never matched by appearance. Different chats or pages share a layout and look identical at thumbnail scale, so they're only grouped when the files are byte-identical.

Two guards came out of testing on a real 18,815-photo library:

- **Camera-roll photos are never pre-selected as chats, memes or forwards.** The camera can't produce those, yet the model put 2.9% of camera photos in "Memes" at 0.9+. They still appear under "worth a look".
- **Camera raw files are never analysed.** They are deliberate originals; the model labelled some "Memes", and "Similar photos" would pair each with its JPEG twin.

## WhatsApp's hidden folders

WhatsApp puts a `.nomedia` file in `WhatsApp Images/Sent`, `Video/Sent`, `Animated Gifs/Sent`, the matching `Private` folders (chats with media visibility off), `Stickers` and `Video Notes`. MediaStore still indexes those files, but as plain files rather than photos or videos, so no app with photo access can see them. On the test phone they held 12.8 GB, against the 1.9 GB of clutter DustIt found everywhere else.

- **Access.** The user shares WhatsApp's `Media` folder once through the system folder picker, which DustIt opens on that folder. The grant is persisted and can be dropped in Settings. A folder that isn't WhatsApp's Media folder (or a folder above it) is refused with an explanation. No all-files permission is involved.
- **Indexing.** Only the known hidden folders are listed (`whatsapp/WhatsAppLayout.kt`), never the visible ones next to them. Their files get negative ids derived from the document id, so they share the rest of the pipeline: hidden photos are classified, grouped with gallery photos and offered in the usual groups, and hidden videos count as large videos. Stickers are not classified. A file MediaStore also shows is never indexed a second time, because one file under two ids would look like a byte-identical duplicate of itself.
- **Speed.** The provider checks every file when a folder is listed, about 1.5 ms per file: 20 s for the test phone's 13,000-file Sent folder. So each folder's modification time is stored and only folders that changed are listed again. With nothing new, the check takes about 0.6 s there: one query per top-level folder, plus one listing wherever there are subfolders (Video Notes keeps a folder per week).
- **Trash.** The system trash only takes photos, videos and audio, so these files go to DustIt's own trash instead. A trashed file is renamed in place (`IMG-…jpg.dustit-trash-<time>`), which stops WhatsApp and galleries opening it, and deleted for good after 30 days. Everything needed to restore it is in the name, so a database reset loses nothing. Settings shows the trash, with Restore all and Delete now. Disconnecting puts trashed files back first, since without access DustIt could never clear them.
- **Trash speed.** Every rename makes Android re-index the file. On the test phone that is 148 ms per file one at a time, 42 ms with four in flight and 38 ms with eight, so four are used; a delete costs about the same as a rename. Big batches therefore run in the app's scope with progress, commit every 100 files, and keep going if the user leaves the screen.

## How it fits together

```
MediaStore ──sync──▶ media ──analyse──▶ analysis ──regroup──▶ similar_member
 (images+videos)   ▲        (classifier + 16×16 thumb,          (exact dupes + appearance)
WhatsApp hidden ───┘         hash, sharpness)
 folders (granted)
                                     └──────────▶ CleanupRules ──▶ Home · Review · Reminders
```

- **`data/`**: Room mirror of MediaStore (`media`), per-image analysis (`analysis`) and similar-photo sets (`similar_member`). `ImageAnalyzer.VERSION` re-analyses and `GROUPING_VERSION` regroups when that logic changes; neither re-indexes.
- **`ml/`**: the MobileNetV3-Small classifier from [OrganizoCV](https://github.com/ChiragKalra/OrganizoCV), run with LiteRT. Input is 224×224 RGB float in 0–255, decoded straight from the original at a reduced sample size. `FeatureExtractor` computes the similarity features from the same pixels.
- **`scan/`**: `Scanner` runs sync, analysis and regrouping; several decoders feed one inference loop, so decoding overlaps with the model. `ScanWorker` runs it for the app, as a foreground service when there's work. `MediaWatchService` runs it in the background when photos or videos change. See [Background scans](#background-scans).
- **`whatsapp/`**: the hidden-folder layout, the folder grant, the folder walker and DustIt's own trash.
- **`reminders/`**: a weekly job that notifies for each group the user follows that has something to clean. Tapping a notification opens that group.
- **`ui/review/`**: a grid with one gesture handler (tap toggles, long-press previews, long-press then drag range-selects with auto-scroll), and a swipe mode. Both edit the same selection.

minSdk 30, which gives `MediaStore.createTrashRequest` (recoverable for 30 days) and scoped storage without the all-files permission.

**Full photo access or nothing.** Android 14+ lets people share only picked photos ("Allow limited access"). DustIt does not support that: it can't find clutter it can't see. In that state it shows a popup explaining why and a button to the app's Settings page, and it never scans, because a sync would cut the index down to the picked photos. `READ_MEDIA_VISUAL_USER_SELECTED` is declared only so the system reports the limited state honestly.

## Storage and performance

DustIt never uses `ContentResolver.loadThumbnail` for photos. MediaStore saves a ~70 KB JPEG for every thumbnail it generates, so scanning a library that way would add gigabytes to the phone being cleaned. Photos are decoded from the original at the size needed, for the scan and for grids alike; only video frames go through MediaStore.

Measured on a OnePlus Nord 6 (Snapdragon 8s Gen 4, Android 16) with 18,815 photos and 1,952 videos:

| | |
|---|---|
| First scan | 184 s (102 photos/s) |
| Rescan with nothing new (every app open) | 0.7–1.0 s; 1.4 s with WhatsApp's folders connected (23,500 more files) |
| First app open after a day of WhatsApp use | 49 s, nearly all of it listing the WhatsApp folders that changed (old results stay on screen meanwhile) |
| New photo, app closed | picked up 10 s later; background scan takes 1.3 s |
| Memory | ~150–180 MB idle; peaks of ~380 MB while scanning |
| Grid scroll, 5,000 cells, optimised build | 7 ms median frame, 2% janky |
| MediaStore thumbnail cache growth | none |

Per photo: ~8.6 ms to open through the media provider, ~24 ms to decode on one thread, 4.6 ms for the model (one thread; more threads don't help a model this small) and 0.8 ms for similarity features.

## Background scans

Four things here were found on the test phone (OxygenOS 16), not on the emulator:

- **The watcher is a plain JobScheduler job, not WorkManager work.** With content-URI triggers under WorkManager, a change made while the app was closed never led to a scan. On every cold start WorkManager found job records left by finished work, rescheduled everything it held, and so cancelled the job that had just woken the app.
- **The background scan runs inside that job.** Handing it to a queued `ScanWorker` left the scan running with no job of its own, and the phone froze the process 11 s after starting it, every time.
- **A background scan only syncs and analyses the gallery.** OxygenOS stops a background job about 5 s in (11 s from a cold start), so `Scanner.run(background = true)` leaves WhatsApp's hidden folders as last listed (one changed Sent folder takes 20 s to list) and leaves regrouping similar photos (2.3–3.2 s over ~38,000 photos) to the next app open. With both in, background scans ran 4.7 s to over 20 s and were mostly cut short. It still drops any hidden-folder row MediaStore now shows too (`keepHiddenFoldersApart`), a database-only check, so a file is never indexed twice.
- **Nothing waits behind an interrupted scan.** `scanAction` in `BackgroundWork.kt` keeps at most one scan running and one queued, and replaces a scan that was started and interrupted. Before that, one scan interrupted ten times sat out a four-hour retry delay with 325 requests queued behind it, and opening the app did nothing.

A scan cut short keeps what it saved, and the groups are marked as needing a rebuild before anything they depend on is written, so the next scan finishes the job.

## Known gaps

- **With the app closed, some groups only catch up when it's opened.** Background scans analyse new gallery photos, so classifier groups (screenshots, memes, chats and the rest) and large videos stay current. Similar photos, duplicates and the four WhatsApp groups update on the next app open, which then takes a few seconds longer, or up to about a minute after a day of WhatsApp use. Weekly reminders for those groups count what was there at the last app open. This is forced by OxygenOS's 5 s background limit; other phones built on the same system probably behave alike, none were tested.

- **Sent videos aren't matched to their originals.** Sent photos are, by appearance. WhatsApp re-encodes videos, and no duration is on record for hidden files, so sent videos are offered largest first and never pre-selected.
- **Deleting thousands of WhatsApp files takes minutes**, because Android re-indexes each one. It runs in the background with progress, but there's no notification yet if the app is closed part-way.
- **Classifier precision.** "Chats" is clean (0 of 518 camera photos at any score). "Memes" is noisy: 18% of camera photos score 0.6+, 2.9% score 0.9+, 0% score 0.99+. A per-category threshold or a retrained model is the open decision.

## Build & test

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest            # JVM: rules, grouping, features, selection, sync, WhatsApp layout
./gradlew :app:connectedDebugAndroidTest    # device: scan → weekly reminder smoke test
./gradlew :app:assemblePerf                 # release-optimised, debug-signed: use this to judge performance
```

`connectedDebugAndroidTest` uninstalls the app when it finishes, which wipes its data and permissions on that device. To run a device test against an install you want to keep, install both APKs and use `am instrument`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.bruhascended.dustit.ScanBenchmark \
    com.bruhascended.dustit.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s DustItBench:I DustIt:I   # per-phase timings; each scan also logs a "scan:" line
```

`ScanBenchmark#wholeScan` runs two whole scans the way the app does, without opening it. That is the only way to time a scan on a phone that is in use, and instrumented tests aren't frozen in the background.

`whatsapp.HiddenTrashBenchmark` times DustIt's trash renames on a device, using its own files in the real Sent folder (connect WhatsApp's folders in the app first). It deletes them again afterwards.

`JunkClassifierTest` checks that the `JunkCategory` enum order still matches the labels embedded in the `.tflite`. Run it after swapping the model. The similarity thresholds in `SimilarityGrouper` are first guesses and still need tuning against real galleries.
