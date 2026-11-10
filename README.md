# Music Video Studio

**Music Video Studio** is an Android music video creation and non-linear Edit Decision List (EDL) workstation built with **Kotlin**, **Jetpack Compose (Material 3)**, **Room SQLite**, **WorkManager**, and **Firebase AI Logic**.

It combines AI-assisted multimodal storyboarding and lyric synchronization with a deterministic, nondestructive timeline editor, a crash-safe media ingestion pipeline, and local hardware-accelerated MP4 rendering and export.

---

## Key Features

### 1. Multimodal AI Storyboarding & Lyric Alignment
- **Audio + Reference Photo Analysis**: Uses Firebase AI Logic (`Firebase.ai`) to generate structured scene storyboards, camera movement presets, lighting cues, and synchronized lyrics from an imported audio track (MP3/WAV/FLAC/M4A) or built-in procedural synth presets, a reference photograph, and a creative prompt.
- **Audio Payload Safety**: Validates MIME types and file sizes up front (`AudioPayloadValidator`), refusing silent track truncation.

### 2. Nondestructive EDL Workstation (`EdlTimelineEngine`)
- **Three-Track Editorial Timeline**:
  - **Video Scene Track**: Reorder scenes, adjust durations with contiguous boundary reflow, switch camera movement presets (`SLOW_PUSH_IN`, `BASS_PUMP_ZOOM`, `ORBITAL_SWEEP_LEFT`, etc.) and transitions (`CROSS_DISSOLVE`, `FLASH_CUT`, `WHIP_PAN`, etc.), regenerate individual scenes, and restore previous scene versions (`SceneVersionSnapshot`).
  - **Lyric / Caption Track**: Edit transcribed lyrics, toggle chorus highlights, adjust start/end timestamps, nudge captions along the timeline, or insert/delete lines while tracking provenance (`TRANSCRIBED_FROM_AUDIO` vs. `USER_CORRECTED`).
  - **Audio Track**: Split audio at arbitrary source timestamps, nondestructively mute/remove segments, and trim start/end ranges with automatic edited-to-source timestamp mapping.
- **Full History Stack**: Supports multi-step **Undo**, **Redo**, and **Restore Original Project** while preserving approved per-scene version snapshots.
- **Zero-Path EDL Invariant**: `EdlSceneClip`, `SceneVersionSnapshot`, `StoryboardCue`, and serialized EDL/history JSON reference verified video clips exclusively by immutable `assetId` (`asset_<sceneId>_v<NN>`), never storing raw filesystem paths.

### 3. Provider-Independent Media Ingestion Pipeline (Phase 2A–2C)
- **WorkManager Orchestration (`GenerationWorkOrchestrator`, `LocalImportWorker`)**:
  - Enqueues local MP4 scene imports with `ExistingWorkPolicy.KEEP` and minimal input data (`keyValueMap.keys == setOf("jobId")`), loading the durable `sourceUri` and `sceneId` from Room.
- **Streaming Validation & Probing (`MediaAssetValidator`, `MediaExtractorProbe`, `VfrDetector`)**:
  - Streams source files in 64 KB chunks into `filesDir/.staging_tmp/job_<jobId>.part` and computes streaming SHA-256 digests (`ChecksumVerifier`).
  - Uses Android `MediaExtractor` (`MediaExtractorProbe`) to discover video tracks regardless of audio/video track ordering and performs bounded presentation timestamp (PTS) cadence analysis (`DEFAULT_MAX_SAMPLE_COUNT = 600`) to classify Constant Frame Rate (CFR) vs. Variable Frame Rate (VFR), returning `isVfr = null` / `scanComplete = false` if sample caps truncate a clip.
  - Verifies actual frame decodability separately via `FrameDecodabilityVerifier` (`MediaMetadataRetriever`).
- **Durable Promotion Journal & Atomic Commit (`PromotionJournalEntity`, `AtomicFilePromoter`, `StudioDatabase`)**:
  - Records exact `(jobId, assetId)` ownership in `promotion_journal` (`PREPARED`) before moving the staged file via POSIX `Files.move(..., ATOMIC_MOVE)` into `filesDir/studio_assets/<assetId>.mp4`.
  - Advances the journal to `PROMOTED` and executes a single `RoomDatabase.withTransaction {}` (`StudioDatabase.commitPromotedAsset`) that atomically inserts `AssetManifestEntity`, marks `GenerationJobEntity` `COMPLETED`, and transitions `PromotionJournalEntity` to `COMMITTED`.
- **Exact-Job Crash Recovery (`StorageReconciliationManager`)**:
  - Recovers interrupted `PREPARED` and `PROMOTED` journal entries deterministically by `jobId` across all crash windows and quarantines unreferenced files without ever guessing job ownership from filenames or `sceneId`.

### 4. Hybrid Real-Time Preview & Local MP4 Export
- **Hybrid Scene Rendering (`VideoFrameRenderer`, `SceneVideoFrameExtractor`)**:
  - Scenes with a verified `assetId` resolve their MP4 file at the rendering boundary through `AssetManager` / `SceneVideoFrameExtractor`, while scenes without a video asset render procedural camera motion, beat-reactive lighting, and color filters over the reference photograph.
- **Local Hardware MP4 Export (`HdVideoExporter`)**:
  - Encodes the edited timeline (`MediaCodec` H.264 video + AAC audio via `MediaMuxer`) synchronized to the active EDL audio segments and lyric overlays.

---

## Tech Stack & Resolved Versions

| Component | Version |
| :--- | :--- |
| **Gradle Wrapper** | `9.3.1` |
| **Android Gradle Plugin (AGP)** | `9.1.1` |
| **Kotlin** | `2.2.10` |
| **KSP (Kotlin Symbol Processing)** | `2.3.5` |
| **Compile SDK / Target SDK / Min SDK** | `36` / `36` / `24` |
| **JDK** | `21` (Eclipse Temurin 21) |
| **Room (`room-runtime`, `room-ktx`, `room-compiler`, `room-testing`)** | `2.7.0` |
| **WorkManager (`work-runtime-ktx`, `work-testing`)** | `2.12.0` |
| **Jetpack Compose BOM** | `2024.09.00` |
| **Firebase BOM (`firebase-ai`, `firebase-appcheck`)** | `34.17.0` |
| **Robolectric** | `4.16.1` |

---

## Project Structure

```text
├── app/
│   ├── schemas/com.example.studio.storage.db.StudioDatabase/
│   │   ├── 1.json                          # Room v1 exported schema
│   │   └── 2.json                          # Room v2 KSP-exported schema
│   └── src/
│       ├── main/java/com/example/
│       │   ├── MainActivity.kt             # App entry point & Studio UI host
│       │   ├── data/                       # Firebase AI storyboard repository & audio validator
│       │   ├── media/                      # EdlTimelineEngine, VideoFrameRenderer, SceneVideoFrameExtractor, HdVideoExporter
│       │   ├── model/                      # StudioModels (EdlSceneClip, SceneVersionSnapshot, StoryboardCue, ProjectEdl)
│       │   ├── studio/
│       │   │   ├── ingestion/              # AssetManager, MediaAssetValidator, MediaExtractorProbe, VfrDetector, StorageReconciliationManager
│       │   │   └── storage/
│       │   │       ├── db/                 # StudioDatabase (v2 + MIGRATION_1_2), Entities, DAOs
│       │   │       └── workers/            # GenerationWorkOrchestrator, LocalImportWorker, AssetDownloadWorker
│       │   ├── ui/                         # Jetpack Compose workstation screens & timeline components
│       │   └── viewmodel/                  # MusicVideoViewModel
│       └── test/java/com/example/
│           ├── ExampleUnitTest.kt          # Core EDL, audio validator & storyboard unit tests (12 tests)
│           ├── ExampleRobolectricTest.kt   # Baseline Robolectric tests (2 tests)
│           ├── Mp4TestFixturesAndShadows.kt# Synthetic MP4 generator & MediaExtractor/MediaMetadataRetriever shadows
│           ├── Phase2IngestionPipelineTest.kt # Phase 2A/2B persistence & ingestion tests (24 tests)
│           └── Phase2CAcceptanceTest.kt    # Phase 2C SQLite migration, journal crash-window & worker acceptance tests (17 tests)
├── gradle/
│   ├── libs.versions.toml                  # Centralized version catalog
│   └── wrapper/                            # Authentic Gradle 9.3.1 wrapper JAR & properties
├── gradlew                                 # POSIX Gradle wrapper script (0755)
└── gradlew.bat                             # Windows Gradle wrapper script
