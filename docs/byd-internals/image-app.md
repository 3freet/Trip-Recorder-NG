# The Image app (`com.byd.avc`)

The car's own surround-view app, called **Image** on the screen. It draws the 360° views, the reversing picture
and the turn-signal blind-spot windows. Everything below is from its decompiled code on the same unit and from
watching it work (see [README](README.md)); observed on one vehicle only.

Package `com.byd.avc` (the APK is `AutoVideo.apk`), system uid, persistent. Logic lives in classes such as
`rule/ruleImpl/process/light/ProcessLightMsg`, `ui/uiImpl/PIP/PIPViewAlertController`, `PIP2MeterAlert`,
`camera/AVMCameraPVC`, `pano/BYDAPI`, `aidlserver/AVCAIDLService`.

## The turn-signal setting

**Triggered by Turn Signal**, in the app's settings, has three choices:

- a **floating window**: the left signal opens it on the instrument cluster, the right signal on the centre
  screen (or, as a variant, both on the centre screen only);
- **full screen** in D at 15 km/h or less;
- off.

A separate option shows the wheel-hub blind spot on the cluster below 10 km/h. The settings are kept in
`/collect2/autovideo/initSettingParam.json` (the ADB shell cannot read it); the keys are `light_active` (1 floating
window, 2 full screen, 3 off) and `light_active_pip` (1 cluster and centre, 2 centre only).

The light messages are handled in `ProcessLightMsg`; the engine is told the lamp state with `setLightStatus`
(for a left signal `lightp 4 status 1` was logged).

## Its windows

Watching the signal stalk while parked:

| Signal | Mode id | Window |
| --- | --- | --- |
| left | 5095 (PIP LEFT) | on the instrument cluster, in a window on the cluster's virtual display |
| right | 5096 (PIP RIGHT) | on the centre screen, top left, about 720 × 450 px on the 2560 × 1440 screen, labelled "Right Rear", with a close × and a "<>" arrow |

When the right-side view is open in the app itself (a pane next to the top-down car strip) it is labelled
`right_2d`: a nearly square picture of the ground beside and ahead of the car, the car's side at the left edge, the
road running away upward. It is a view from a virtual camera at the real lens with a far off-centre principal point;
see [blind-spot-cameras.md](blind-spot-cameras.md#rebuilding-the-cars-own-side-view) for how it was reproduced.

The window closes about two seconds after the signal goes off. The full-screen modes are 5001 (front), 5003 (left)
and 5004 (right); the front-facing side views are reached by tapping in the app. All of them use the 360° feed,
camera id 2 ([blind-spot-cameras.md](blind-spot-cameras.md)). The picture in the "Right Rear" window varied
between captures within one run (a rear-facing view, later a floor view with a painted arrow); what switches it
was not worked out, the "<>" arrow is the likely control.

How the app opens the camera: the blind-spot PIP uses `CameraProxy("PIPCamera").open()`, which ends in
`AVMCamera.open(id, 0)`; its panorama screens use `open(1)`. The id comes from the camera-info class by type
(`pano_h`, `rear`, and a "right front" type, `CAMERA_CAR_RF`, that this car does not list). Both modes returned the same 7680 × 1300 feed here.

The cluster window is described in [cluster-overlay.md](cluster-overlay.md); the cluster shows only the 686 × 386 rectangle where this app's own window goes.

## Exported parts

`AutoVideoActivity`, `PIP2MeterActivity` (action `com.byd.avc.START_TOP_LEFT_ACTIVITY`), `AutoVideoService` and
`AVCAIDLService`. Nothing that asks the app to show a side view was found among them, except the rendering
service below.

## The exported rendering service

`AVCAIDLService`, action **`com.byd.avc.aidl.service`**, package `com.byd.avc`, exported with **no permission**.
Interface descriptor `com.byd.avc.aidl.IAVCAidlInterface`. Called with raw `Binder.transact` (no generated
stub):

| Code | Call |
| --- | --- |
| 1 | `getName()` → "AVC AIDL" |
| 5 | `setViewpoint(int)` |
| 6 | `getSupportPushBufferType()` → 2 on this car |
| 7 | `initDisplay(Surface)` → boolean |
| 8 | `getCameraSurface()` → `Surface` (the engine's camera input) |
| 9 | `freeDisplay()` |
| 10 | `setuTurnEnable(...)` |

A flow that worked from an ordinary app: bind; `initDisplay` with a `Surface` of ours (from a `SurfaceTexture`, or
a `SurfaceView` on screen); `getCameraSurface`; open `AVMCamera(2, 1)` and `addPreviewSurface(thatSurface, 0)`
then `startPreview()`; `setViewpoint(n)`. The engine (native, `libavm`, in the Image app's process) then draws
onto our surface: a strip on the left (the car seen from above, 450 of 1280 pixels), the selected view on the
right, the date and time stamped at the top right; radar warnings tint the picture red and guide lines are drawn
in the front and rear views.

### Which viewpoints draw

| Viewpoint | Engine id | Result |
| --- | --- | --- |
| 2000, 2001 | 1, 0 | front and rear views (guide lines in D) — drew |
| 2002, 2003 | 2, 3 | left and right side views (what the app's Left/Right buttons show) — drew |
| 3203, 3204, 3205 | 84, 85, 86 | the blind-spot PIP views — **blank** |
| 2004–2007 | 4864, 4865, 4608, 4609 | corner views — blank |
| 2013–2029, 2030–2033, 2037–2052, 3001–3030 | — | blank |

"Blank" held in P and in D, with 1280 × 720 and 686 × 386 surfaces, and with the **left turn signal held on while
a client was attached** (the app's listener fed the engine `setLightStatus`), so the signal state is not what
unlocks the PIP views. The probable cause is the engine's scene mode (`sceneRenderSetMode`), which only the
app's own mode machine sets; the service has no call for it. So through this service an app gets the four plain
views and not the rear-facing blind-spot ones.

### One window at a time

The engine has **one display surface**: whichever caller used `initDisplay` last gets it. Tested twice, with the
engine's picture drawn live in a box at the bottom of the centre screen and the right signal switched on: while the
signal was off the box showed the right-side view with a changing timestamp; the moment the car's own "Right Rear"
window opened (it calls `initDisplay` itself), the box stopped changing and kept its last frame, while the car's
window drew normally. So an app cannot use the engine's calibrated views **next to** the car's turn-signal window;
it can only use them **instead of** it, with the car's trigger switched off.

## Flags the app sets around its cluster window

On a model that shows the picture on the cluster it writes, in this order, around its window (code reading, then
tested in [cluster-overlay.md](cluster-overlay.md)):

- ADAS `ADAS_AVM_VIDEO_STREAMING_DISPLAY_STATUS_SET` (id 850452533): 1 normal, 0 default, 2 error;
- panorama `setPanoramaTransparence(1)`, `setRFCameraSwitchState(1)` (on a model with such a switch);
- panorama `PANORAMA_SCREEN_PROJECTION_STATUS_IVI_TO_INS_SET` (id 1309671486): 1 on its first drawn frame
  (`PIP2MeterAlert.onFirstFrame`), 2 when the window is hidden. The only one that mattered in the test.

On models where `isSupportControlSR()` is true it uses `startContentProjection()` (a content-projection service,
`ScreenPosition.CLUSTER_LEFT`, `ContentType.PICTURE_IN_PICTURE_CARD`) instead of its own window; that path was not
seen on this car.

## How the app builds its 360° views

All of this is from the app's files and its native library on the unit; nothing was copied.

- The views come from the surround-view engine, the native library `libavmapi.so` (with `libarcsoft_avm.so`
  alongside), driven from Java by `com.byd.carvis.AvmAPI`. The Java GL package `com.byd.glavm` in the app is a
  generic leftover and is not what draws on this car.
- Each view is a **mesh with per-vertex texture coordinates** (a lookup table generated from the camera calibration by
  the engine's LUT generator, `LoadLutFile`, files such as `3d_srv_lut.bin`, `doubleView_lut.bin`) drawn with the
  camera picture as an external texture. The fragment shader only multiplies the colours by a gain,
  **`light_up_val` = 1.23** (from `configs/avm_param.yaml`), and rounds the corners of a view; there is no
  sharpening and no noise reduction.
- The calibration files in the APK (`resource_byd/<model>/1300P/calibration_param_init.yaml`,
  `CarModel/calib_config/avm_config.yaml`, `CarModel/configs/avm_param.yaml`) hold the lens model, the pose of every
  camera, the vehicle's size, the views' virtual cameras (`LeftRightViewParam`, `DoubleViewParam`, `PiPViewParam`), the
  screen layouts (for the 1440p landscape layout the right pane is 1412 × 1272 at x = 1094) and per-view crop boxes. See
  [blind-spot-cameras.md](blind-spot-cameras.md#the-cars-own-camera-calibration).
- The model family is chosen by a car type string (`SFW` on this unit, resource sets `sfhd`, `sfhe`, `sfhw` and
  `qzhf`); the sets differ only slightly.

## When the car's window exists, and asking for the setting

The car's settings screen for this feature is "Triggered by Turn Signal". Its three choices, from the app's own text
(`string/light_active_*`), all tied to **gear D**:

| Choice | The car's text | What the Image app stores |
| --- | --- | --- |
| Floating window, instrument | in gear D the left signal's window is on the instrument, the right signal's on the centre screen | light type 1, PIP type 1 |
| Floating window, centre screen | in gear D the window is on the centre screen only | light type 1, PIP type 2 |
| Full-screen image | in gear D at 15 km/h or less, left again at 30 km/h or more | light type 2 |
| Off | the signal triggers nothing | light type 3 |

So the car's own window on the cluster exists **only in D** (not in P, R or N) and only for the first choice; the
code that decides it is `PIPViewAlertController.isShowOnMeterAlert`: the mode is the left one (5095), the setting is
PIP type 1, the instrument is the big one and the cluster's display exists. The values are kept in
`/collect2/autovideo/initSettingParam.json` (`light_active_pip`), which the shell cannot read.

**It can be asked, read-only, by an ordinary app.** `com.byd.avc.AutoVideoService` is exported with no permission (action
`com.byd.action.AVCSERVICE`) and returns a `Messenger`. Sending it a `Message` with `what = 1011` and a `replyTo` makes it
answer `what = 1012` with `arg1` = 1 (the cluster can show a window), 2 (it cannot) or 3 (no such setting on this car) and
`arg2` = **0** floating window with the left signal on the instrument, **1** floating window on the centre screen only,
**2** full-screen image, **3** off. (`what = 1013` with `arg1` 0 or 1 *sets* the position; it was not sent.) On this car it
answered 0, matching what the car does. Binding with flags 0 connects to the running service without starting anything.

With the setting on **full screen** the car covers the centre screen with its own picture of the signalled side, for either
signal, in D at 15 km/h or less (and keeps it until 30 km/h), so a second window of ours there would sit on top of the same
picture; ours stays off the centre screen in that case and still shows on the cluster if that is switched on.
