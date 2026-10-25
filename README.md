<p align="center">
  <img src="./materials/images/brand/xmax-sdk.png" alt="XmaxSDK — Realtime Interactive Video Generation" width="880">
</p>

<p align="center">
  <a href="https://developer.android.com/about/versions/oreo"><img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84" alt="Android 8.0+"></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.3-7F52FF" alt="Kotlin 2.3"></a>
  <a href="https://platform.xmaxai.com/"><img src="https://img.shields.io/badge/Realtime-AI-FF9500" alt="Realtime AI"></a>
  <a href="./LICENSE"><img src="https://img.shields.io/badge/License-MIT-4C9A2A" alt="MIT License"></a>
</p>

We introduce XmaxSDK, a native Android SDK designed for real-time interactive video generation via Xmax models. XmaxSDK implements an end-to-end pipeline covering media acquisition, video streaming, frame-by-frame generation, and on-device rendering, enabling developers to seamlessly integrate low-latency, high-fidelity video transformations into creative applications at a much lower cost than alternative solutions.

<p align="center"><img src="./materials/images/xlab/generation-demo.gif" alt="X-Lab realtime generation demo" width="33%" /><img src="./materials/images/xlab/index-demo.gif" alt="X-Lab index demo" width="33%" /><img src="./materials/images/xlab/storage-demo.gif" alt="X-Lab storage demo" width="33%" /></p>

<br>

## What you can build with XmaxSDK

<table>
  <tr>
    <th width="24%" align="left">Realtime Use Case</th>
    <th width="60%" align="left">Description</th>
    <th width="16%" align="center">Demo</th>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Character Swapping</strong>
    </td>
    <td width="60%" valign="middle">
      Replace anyone in your live feed with a designated avatar in real-time.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./materials/videos/use-cases/character-swapping.mp4">
        <img src="./materials/images/use-cases/character-swapping-poster.png" alt="Play the Character Swapping demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>视频中角色替换成参考图中角色</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the desired character with a clean background.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Virtual Try-On</strong>
    </td>
    <td width="60%" valign="middle">
      Seamlessly change outfits, preserving exact body shape, natural motion, and an
      authentic fit.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./materials/videos/use-cases/virtual-try-on.mp4">
        <img src="./materials/images/use-cases/virtual-try-on-poster.png" alt="Play the Virtual Try-On demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>视频中人物衣服替换成参考图中衣服</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the target outfit with a clean background.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Video Restyling</strong>
    </td>
    <td width="60%" valign="middle">
      Reimagine your world in any style with an immersive visual experience.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./materials/videos/use-cases/video-restyling.mp4">
        <img src="./materials/images/use-cases/video-restyling-poster.png" alt="Play the Video Restyling demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>视频风格变为参考图指定的风格</code>
      <br><br>
      <strong>Reference image:</strong> Select an image that captures the artistic style you want to apply.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>AI Companions</strong>
    </td>
    <td width="60%" valign="middle">
      Summon virtual characters into your live camera feed and interact with them
      through gestures.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./materials/videos/use-cases/ai-companions.mp4">
        <img src="./materials/images/use-cases/ai-companions-poster.png" alt="Play the AI Companions demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>指定角色在场景中互动</code>
      <br><br>
      <strong>Reference image:</strong> Select a clear image of the virtual character you want to summon with a clean background.
    </td>
  </tr>
  <tr>
    <td rowspan="2" width="24%" valign="middle">
      <strong>Live Photo</strong>
    </td>
    <td width="60%" valign="middle">
      Animate and control characters in your images simply by drawing motion
      trajectories.
    </td>
    <td rowspan="2" width="16%" align="center" valign="middle">
      <a href="./materials/videos/use-cases/live-photo.mp4">
        <img src="./materials/images/use-cases/live-photo-poster.png" alt="Play the Live Photo demo" width="120">
        <br>
        <sub>▶ Play demo</sub>
      </a>
    </td>
  </tr>
  <tr>
    <td width="60%" valign="middle">
      <strong>Prompt:</strong> <code>让画面自然动起来</code>
      <br><br>
      <strong>Reference image:</strong> Use the input image as the reference
    </td>
  </tr>
</table>

<br>

## Why XmaxSDK?

<table>
  <thead>
    <tr>
      <th height="104" align="center" valign="middle">
        <img src="./materials/images/why/low-latency.svg" alt="Low latency" width="36" height="36"><br>Low latency
      </th>
      <th height="104" align="center" valign="middle">
        <img src="./materials/images/why/low-cost.svg" alt="Cost efficiency" width="36" height="36"><br>Cost efficiency
      </th>
      <th height="104" align="center" valign="middle">
        <img src="./materials/images/why/high-fidelity.svg" alt="High fidelity" width="36" height="36"><br>High fidelity
      </th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>End-to-end latency is measured in <img src="./materials/images/why/latency-highlight.svg" alt="hundreds of milliseconds" width="192" height="20" align="absmiddle">, ensuring that updates to generation conditions and interaction controls are reflected instantly.</td>
      <td>Run on a <img src="./materials/images/why/gpu-highlight.svg" alt="single RTX 5090" width="126" height="20" align="absmiddle">, reducing inference costs by orders of magnitude versus datacenter GPUs like H100.</td>
      <td>Our models support real-time generation at up to <img src="./materials/images/why/resolution-highlight.svg" alt="1080p" width="48" height="20" align="absmiddle">, delivering production-ready, high-quality video output.</td>
    </tr>
  </tbody>
</table>

<br>

## Prerequisites

- Android 8.0 (API level 26) or later
- Kotlin 2.3 and JDK 17
- Android SDK 37 for building from source
- An Xmax API key

> [!WARNING]
> Never commit your Xmax API key to version control. Pass it securely at runtime or
> use short-lived temporary keys issued by the Xmax API. For step-by-step
> instructions, see [Authentication](https://platform.xmaxai.com/docs/authentication).

<br>

## Installation

XmaxSDK is distributed through [**Maven Central**](#maven-central).

### Maven Central

XmaxSDK uses Agora RTC and supports `RealtimeModel.X2_1_1_PREVIEW_CN` and
`RealtimeModel.X2_1_1_PREVIEW_US`. Select China or global service routing through
`XmaxConfiguration.environment`. The quality controls and unified remote
audio/video output described below are available starting in version 1.0.12.

When upgrading to **1.0.12**, replace `setRemoteVideoFrameListener(...)` with
`setRemoteMediaFrameListener(...)` and handle its audio and video packet variants.
External sources providing their own preview must override `providesPreview` to
`true`; otherwise XmaxSDK renders the submitted frames.

The current source renames single-track views to `XmaxRealtimeVideo` and
`XmaxRealtimeVideoView`, and dual-track views to `XmaxRealtimeDualVideo` and
`XmaxRealtimeDualVideoView`. The examples below use these new names; use a source
dependency until the next release is published. Version 1.0.12 uses `XmaxVideo`
and `XmaxVideoView` for single tracks, and `XmaxRealtimeVideo` and
`XmaxRealtimeVideoView` for dual tracks. The old names are not retained as aliases
in the current source.

When upgrading from **1.0.10 or earlier**, also remove the `videoFormat` argument from
`createLocalImageStream(...)` and `createLocalVideoStream(uri)` calls and rebuild
your application. The SDK now derives image and local video input formats from
the media dimensions and model defaults.

Configure the repositories used by your application in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

Enable AndroidX in your application's `gradle.properties`:

```properties
android.useAndroidX=true
```

XmaxSDK no longer requires Jetifier or the VolcEngine repository. Keep them only
if other dependencies in your application still need them.

Add XmaxSDK to your application module:

```kotlin
dependencies {
    implementation("ai.xmax:xmax-sdk:1.0.12")
}
```

Gradle resolves Agora RTC and the SDK's other runtime dependencies transitively.
To debug the SDK locally, open this repository and use the XLab example or a
local module dependency.

<br>

## Quick Start

### Configure permissions

Declare internet and camera access in your application's `AndroidManifest.xml`.
Add microphone access when camera audio is enabled:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />

<uses-feature
    android:name="android.hardware.camera"
    android:required="false" />
```

Your application must request camera permission at runtime before creating a camera
stream. When `useMicrophone` is enabled, request microphone permission as well.
XmaxSDK throws an `XmaxError` if a required permission is denied or unavailable.

<br>

### Generate and display video

The following snippet creates a camera stream, starts real-time generation, and
binds the output to a video view. Call suspending SDK APIs from an application-owned,
lifecycle-aware coroutine scope.

```kotlin
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeConfiguration
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeModel
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration
import ai.xmax.sdk.XmaxRealtimeDualVideoView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

val client = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(apiKey = "YOUR_XMAX_API_KEY"),
)

val realtime = client.createRealtimeManager(
    options = RealtimeConfiguration(model = RealtimeModel.X2_1_1_PREVIEW_CN),
)

val videoView = XmaxRealtimeDualVideoView(this).apply {
    videoContentMode = VideoContentMode.FILL
    remoteVideoFadeInDuration = 0.3
}
setContentView(videoView)

lifecycleScope.launch {
    val localStream = realtime.createLocalCameraStream(
        videoFormat = RealtimeVideoFormat(width = 1024, height = 1920, fps = 30),
        position = CameraPosition.FRONT,
    )
    videoView.localTrack = localStream.videoTrack

    val remoteStream = realtime.startGeneration(
        localStream = localStream,
        context = RealtimeContext(
            prompt = "视频中角色替换成参考图中角色",
            referencePath = "https://platform.xmaxai.com/images/source/charx/chatx_image1.jpg",
        ),
    )
    videoView.remoteTrack = remoteStream.videoTrack
}
```

`XmaxRealtimeDualVideoView` displays the local camera preview until the first generated
frame arrives, then transitions to the remote video. Touch interaction is enabled by
default after the generated video becomes visible.

Set `remoteVideoFadeInDuration` to customize the remote fade-in duration in
seconds. The default is `0.3`; zero, negative, or nonfinite values show the remote
video without animation after its first frame arrives. Changes apply to the next
transition without changing an active animation.

Local camera, image, video, and external input dimensions are resized into the model's
minimum and maximum pixel-area limits and aligned to multiples of four, preserving
the exact aspect ratio where possible. Both preview models accept processed media
between 200,000 and 2,100,000 pixels; smaller inputs are enlarged and larger inputs
are reduced. Only camera requests must match a supported model resolution bucket.

The SDK selects the generation canvas (`size`) separately by the closest model
aspect ratio. Live frame input uses `padding: "contain"` only when its aspect ratio
differs from the canvas. Uplink and downlink quality use the processed media
dimensions as their fixed baseline, not the model canvas. Downlink `target_size`
is aligned to multiples of four and retains the media aspect ratio where possible.

Touch trajectories are sent in `tracks` as normalized `[0, 1]` coordinates based
on the fixed, processed local media dimensions, accounting for FIT letterboxing
and FILL cropping. Downlink resolution changes do not change this mapping. The
server must interpret these as relative positions, not pixel coordinates.

Overlapping `startGeneration` calls on the same manager use the latest request.
The SDK cancels superseded calls with `CancellationException` and waits for their
cleanup before starting the latest request; no application-side generation queue is needed.
`disconnect()` and `close()` cancel pending generation requests, so they cannot restart later.
Other conflicting lifecycle operations still require coordination by the caller.

For a network driving video, create a URL-backed source. The server must be able
to fetch the URL directly; local request headers and Android URI permissions are
not forwarded. The network source joins RTC without publishing local media:

```kotlin
import android.net.Uri

val localStream = realtime.createNetworkVideoStream(
    uri = Uri.parse(drivingVideoUrl),
    videoFormat = RealtimeVideoFormat(width = 1080, height = 1920, fps = 30),
    onFinish = {
        // Called once on Main for the current task after the server reports completion.
        // Remote tail frames can still arrive; this does not close the session.
    },
)
videoView.localTrack = localStream.videoTrack
val remoteStream = realtime.startGeneration(
    localStream,
    RealtimeContext(prompt = "Replace the character", referencePath = referenceImageUrl),
)
videoView.remoteTrack = remoteStream.videoTrack
```

Network preview is silent and plays once, retaining the last frame while attached.
The default server sampling method is `RealtimeVideoSampleMethod.TIME`; `FPS` is
also available. This source does not upload local audio. Remote audio is subscribed
after the first generated frame is ready, with the same default playback volume
as local video inputs. Recording and muxing remain your app's responsibility.
`onFinish` comes from the current task's `video_stopped` message, independently of
local preview playback, and is delivered only after generation is ready. Generated
output is not cached or looped. Use `disconnect()` to end the session while keeping
the source, or `close()` to release both session and preview resources. Closing or
restarting invalidates pending completion callbacks from the previous task.

For an external player, use `replaceExternalVideoStream` to switch videos on the
same manager. Its `targetSource` callback runs after the previous generation and
frame input have stopped, so a shared player cannot feed the new video into the
old task:

```kotlin
val replacement = realtime.replaceExternalVideoStream {
    playback.prepare(nextIndex) // App-owned player: select and prepare the next video.
    playback.source            // The prepared RealtimeExternalVideoSource.
}
videoView.localTrack = replacement.localStream.videoTrack
videoView.remoteTrack = replacement.remoteStream?.videoTrack
```

The callback uses the caller's dispatcher. An active or pending generation resumes
with the SDK's existing conditions and a new task ID; preview-only input stays in
preview. Matching video/audio configurations retain the room and session. A changed
configuration reconnects internally while retaining the manager and RTC engine.
Overlapping replacements use the latest request, and `disconnect()` or `close()`
prevents a pending replacement from restoring generation. The SDK never releases
the application's player.

Camera input does not use the microphone by default. Opt in when creating the stream:

```kotlin
val localStream = realtime.createLocalCameraStream(
    videoFormat = RealtimeVideoFormat(width = 1080, height = 1920, fps = 30),
    position = CameraPosition.FRONT,
    useMicrophone = true,
)
```

Microphone capture starts when the stream connects and stops on `disconnect()`, while
the camera preview remains available. Reconnecting the same stream starts microphone
capture again. Camera audio is sent to RTC without local playback.

The client uses the China service environment by default. To connect to the global
environment, select it when creating the client:

```kotlin
import ai.xmax.sdk.XmaxEnvironment

val globalClient = XmaxClient(
    context = applicationContext,
    configuration = XmaxConfiguration(
        apiKey = "YOUR_XMAX_API_KEY",
        environment = XmaxEnvironment.GLOBAL,
    ),
)

val globalRealtime = globalClient.createRealtimeManager(
    options = RealtimeConfiguration(model = RealtimeModel.X2_1_1_PREVIEW_US),
)
```

`XmaxEnvironment.CHINA` uses `https://cloud.xmax.22duck.cn/open/api/v1`.
For `XmaxEnvironment.GLOBAL`, realtime sessions use `https://api.xmax.ai/open/api/v1`,
while storage and other API requests use `https://api.xmax.cloud/open/api/v1`.
Use `RealtimeModel.X2_1_1_PREVIEW_CN` for China and
`RealtimeModel.X2_1_1_PREVIEW_US` for the global environment. The default model is
`X2_1_1_PREVIEW_CN`; selecting a model does not change the client's environment.

Both preview models support generation canvases of `1024×1920` and `1920×1024`.
The default camera format is `1024×1920` at 30 fps.
The model's `supportedVideoFormats` lists these sizes with their recommended frame rates.

Create a camera stream using a supported format:

```kotlin
val localStream = realtime.createLocalCameraStream(
    videoFormat = RealtimeVideoFormat(width = 1024, height = 1920, fps = 30),
    position = CameraPosition.FRONT,
)
```

Once the camera preview is `READY` and realtime is disconnected, change its
resolution without replacing the track or rebinding the view:

```kotlin
realtime.updateCameraFormat(RealtimeVideoFormat(width = 1920, height = 1024, fps = 30))
val currentFormat = realtime.currentCameraVideoFormat
val currentPreset = realtime.currentCameraPreset
```

Camera presets are available only when their dimensions match the selected
model's supported formats. The retained presets do not match these preview models;
use a full `RealtimeVideoFormat` instead, and `currentCameraPreset` is `null`.
Unsupported formats or failed changes preserve the previous track configuration.
These media settings are independent of L1–L5
transmission quality levels. When no minimum uplink bitrate is specified, the
SDK uses 500 kbps, capped at the maximum bitrate; explicit values take priority.

Still images and local videos can also be used as input:

```kotlin
val imageStream = realtime.createLocalImageStream(imageUri)
val videoStream = realtime.createLocalVideoStream(videoUri)
```

Image and local video inputs do not accept a `videoFormat` override. The SDK
derives dimensions from the orientation-corrected media, applies the model's
pixel-area limit and four-pixel alignment, and uses the model's default frame rate.

Only one local input stream can be active at a time.

<br>

### Using Jetpack Compose

Use `XmaxRealtimeDualVideo` to keep the local preview visible until the first generated
frame arrives, then transition to the remote video:

```kotlin
import ai.xmax.sdk.XmaxRealtimeDualVideo
import ai.xmax.sdk.VideoContentMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

XmaxRealtimeDualVideo(
    localTrack = localStream?.videoTrack,
    remoteTrack = remoteStream?.videoTrack,
    modifier = Modifier.fillMaxSize(),
    videoContentMode = VideoContentMode.FILL,
    remoteVideoFadeInDuration = 0.3,
)
```

Use `XmaxRealtimeVideo` to display a single local or remote track:

```kotlin
import ai.xmax.sdk.XmaxRealtimeVideo

XmaxRealtimeVideo(
    track = localStream?.videoTrack,
    modifier = Modifier.fillMaxSize(),
    videoContentMode = VideoContentMode.FIT,
)
```

Both components accept `isInteractionEnabled` and `trajectoryRenderer` for remote
touch trajectories. Keep custom renderers in `remember` so recomposition retains
the same instance. The components update their underlying Android views as their
parameters change and unbind tracks when removed from the composition. Keep stream
objects in lifecycle-aware Compose state; the caller remains responsible for
managing media streams and the realtime manager's lifecycle.

You can also embed `XmaxRealtimeDualVideoView` directly with `AndroidView`:

```kotlin
AndroidView(
    factory = { context -> XmaxRealtimeDualVideoView(context) },
    update = { view ->
        view.videoContentMode = VideoContentMode.FILL
        view.remoteVideoFadeInDuration = 0.3
        view.localTrack = localStream?.videoTrack
        view.remoteTrack = remoteStream?.videoTrack
    },
    onRelease = { view ->
        view.remoteTrack = null
        view.localTrack = null
    },
)
```

The Compose components reuse these Android views, including local preview,
remote first-frame transitions, and generated-video touch trajectories.

<br>

### Listen for events

After creating `realtime`, register the listeners you need before creating the input
stream or starting generation.

| Listener | Purpose |
| --- | --- |
| `setStateListener` | Observe pipeline states and terminal failure reasons; camera `READY` means the first local frame can be previewed. |
| `setRemoteMediaFrameListener` | Receive owned PCM16 audio and I420 video with a shared stream identity and presentation timeline. |
| `setNetworkQualityListener` | Monitor uplink and downlink network quality. |
| `setPerformanceAlarmListener` | Detect device performance limitations or recovery, with a suggested video format when available. |

For example, monitor state changes and terminal failures:

```kotlin
realtime.setStateListener { state ->
    println("State: ${state.connectionState.value}")
    val failure = state.reason as? RealtimeReason.Failure
    if (failure != null) {
        println("Error: ${failure.error.code} ${failure.error.message}")
    }
}
```

`PREPARING` and `READY` describe local media preparation. After disconnect, the
manager returns to `READY` when a local preview remains, or `IDLE` otherwise;
`state.reason` distinguishes a normal stop from a failure. Individual suspending
calls still throw their own errors. Listeners are delivered on the main thread,
except remote audio/video frame callbacks,
which run serially on an SDK background dispatcher.

To record or process generated media, register `setRemoteMediaFrameListener` and
handle `RealtimeMediaFrame.Content.Audio` (PCM16 little-endian) and
`RealtimeMediaFrame.Content.Video` (I420). Both tracks share `streamID` and
`presentationTimeUs`; use one recording origin rather than rebasing each track
independently. Audio comes from the remote publisher before user mixing and is
independent of playback volume. Registering the listener does not change audio
subscriptions, enable the microphone, or require a video view.

Packets own their data and can be retained after callbacks return. Delivery is
serial, not timestamp-sorted across tracks. The queue retains at most 256 packets
or 32 MiB, including an executing callback. Slow consumers can lose packets;
`isDiscontinuous` marks the first packet of each track, format/clock changes, and
the next packet after discarded output. A new `streamID` starts a new timeline.
Clearing the listener discards queued output; an executing callback may finish.
XLab demonstrates H.264/AAC recording and MP4 muxing outside the SDK.

<br>

### Adjust transmission quality

Automatic uplink and downlink adaptation is enabled by default. To use manual
quality control only, disable it when creating the realtime manager:

```kotlin
val manualRealtime = client.createRealtimeManager(
    options = RealtimeConfiguration(
        model = RealtimeModel.X2_1_1_PREVIEW_CN,
        isAdaptiveQualityEnabled = false,
    ),
)
```

When disabled, each connection starts at L1 without reading or updating quality
history. Manual quality changes remain available. Network quality, performance,
and uplink/downlink statistics listeners are unaffected.

Read the successfully applied uplink level and successfully submitted downlink
level through `uplinkQualityLevel` and `downlinkQualityLevel`. A direction with no
active configuration returns `null`; both become `null` when disconnecting.
The actual received resolution may change slightly after the downlink property.

While generating, adjust either direction independently:

```kotlin
import ai.xmax.sdk.RealtimeQualityLevel

realtime.setUplinkQualityLevel(RealtimeQualityLevel.L3)
realtime.setDownlinkQualityLevel(RealtimeQualityLevel.L2)
```

L1–L5 target 100%, 90%, 75%, 60%, and 50% of the media baseline's width and
height, with uplink frame-rate limits of 30, 24, 24, 20, and 16 fps. With automatic
adaptation enabled, both directions start at L2 when history is missing, expired,
or invalid. Disabling adaptation starts both directions at L1. Pixel alignment and
minimum dimensions still apply; uplink also retains at least 200,000 pixels, so a
lower tier may exceed its nominal dimension percentage. Downlink does not use
that minimum area.
Uplink encoding dimensions are multiples of four; downlink retains its existing
dimension rules, with the final `target_size` aligned to multiples of four.
Manual choices do not change the media baseline or model canvas, and do not
disable adaptation when it is enabled. Only successful adjustments update the
current level; one-hour history is updated only when adaptation is enabled.
Calls outside generation, or calls that cannot apply the requested configuration,
throw an error without intentionally ending the current generation.

### Upload a reference image

`RealtimeContext.referencePath` accepts a remote image URL. Upload an on-device image
through the storage manager before starting generation:

```kotlin
val storage = client.createStorageManager()

val uploaded = storage.uploadImageFile(
    file = imageFile,
    contentType = "image/jpeg",
) { progress ->
    println(progress.fractionCompleted)
}

val context = RealtimeContext(
    prompt = "视频中角色替换成参考图中角色",
    referencePath = uploaded.url,
)
```

The storage manager obtains temporary credentials from Xmax. Tencent Cloud
credentials are not embedded in the host application.

<br>

### Resource cleanup

- **`disconnect()` — Stop remote generation**

  Stops remote generation and cancels billing while keeping the local media stream
  and preview active. Use it when ending the online session but staying on the same
  screen. You can start a new session later with the same local stream:

  ```kotlin
  realtime.disconnect()
  ```

- **`close()` — Full teardown and release**

  Ends the remote session, stops local media capture, and releases all RTC resources.
  Use it when leaving the generation screen:

  ```kotlin
  realtime.close()
  videoView.remoteTrack = null
  videoView.localTrack = null
  ```

> **Note:** These methods are alternatives, not sequential steps. When exiting a
> screen, call `close()` directly—there is no need to call `disconnect()` first.

<br>

> [!TIP]
> For complete examples covering camera, image, and video inputs, reference image
> upload, custom prompts, touch interaction, recording, and lifecycle handling, see
> the [example project](#example-project).

<br>

## Example Project

A complete example application featuring a Jetpack Compose implementation
is available in
[`examples/XLab`](https://github.com/Xmax-AI/XmaxSDK-Android/tree/main/examples/XLab).
It demonstrates real-time generation using live camera feeds, static images, and
local video files.

<p align="center"><img src="./materials/images/xlab/home.jpg" alt="X-Lab home" width="20%" /><img src="./materials/images/xlab/features.jpg" alt="X-Lab SDK features" width="20%" /><img src="./materials/images/xlab/storage.jpg" alt="X-Lab storage service" width="20%" /><img src="./materials/images/xlab/realtime-generation.jpg" alt="X-Lab realtime generation" width="20%" /><img src="./materials/images/xlab/trajectory-generation.jpg" alt="X-Lab trajectory generation" width="20%" /></p>

<br>

## Dependencies

- <ins><strong>Agora RTC SDK</strong></ins> enables low-latency, real-time audio and video communication.
- <ins><strong>Tencent Cloud COS SDK</strong></ins> handles media upload and download through object storage.

<br>

## Contact us

For bug reports and feature requests, please open a
[GitHub Issue](https://github.com/Xmax-AI/XmaxSDK-Android/issues). For integration
assistance and technical support, contact us at [sdk@xmax.ai](mailto:sdk@xmax.ai).

<br>

## License

XmaxSDK is available under the terms of the [MIT License](LICENSE).
