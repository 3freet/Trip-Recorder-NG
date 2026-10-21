# The cameras, the 360° feed and the turn signal

What cameras this head unit has, how an ordinary app opens the one that matters for the sides of the car, which
of its four pictures is which, how a mirror camera can be turned into a normal-looking view, how the turn signal
is read, and what happens when the car's own turn-signal window is on at the same time. Observed on one vehicle
(see [README](README.md)); every test here was done while parked.

## The cameras the car has

The head unit's camera-info class (`android.hardware.BmmCameraInfo`, static methods
`getAvailableCameraTypeList()`, `getCameraId(String)`, `getDefaultPreviewWidth/Height(int)`) lists five types:

| Type | Id | Default size | What it is |
| --- | --- | --- | --- |
| `pano_h` | 2 | 7680 × 1300 | the 360° feed: four pictures side by side (below) |
| `dms` | 1 | 1280 × 800 | driver monitoring |
| `ims` | 0 | 1920 × 1080 | in-cabin |
| `rear`, `rear_v1` | 11001 | 1920 × 1080 | rear-view camera |

There is **no separate type for a blind-spot or side camera**. The picture the car shows as "Right Rear" or "Left
Rear" (the rear-facing blind-spot view) is drawn by the *Image* app's own engine
([image-app.md](image-app.md)); the app's code opens camera id 2 (the 360° feed) for that window, but the car's owner
reports that the rear-facing side views come from separate cameras. Either way it is not a camera an app can open
here, and only the front-facing side pictures of the 360° feed were used. Android's own camera service lists three
ordinary cameras (`dumpsys media.camera`: devices 0, 1, 2, facing front, front and back); the 360° feed is not
one of them. (An app's `camera2` listing showed ids 1, 2 and 11001; the two listings were not reconciled.)

## The 360° feed

One frame is **7680 × 1300**, four raw fisheye pictures of **1920 × 1300** side by side, left to right:

| Picture | Camera |
| --- | --- |
| 1 | rear |
| 2 | left mirror |
| 3 | right mirror |
| 4 | front |

Which picture is which was worked out by comparing them with the *Image* app's own right-side view and confirmed
by the driver (the order is not the usual front, rear, left, right). Each picture is a circle about 1750 pixels
wide inside the 1920 × 1300 frame, so a field of view of roughly 190°; the top and bottom of the circle are cut
off by the frame. The car's own bodywork shows at the bottom of each side picture, which helps to tell them apart.
In the right-mirror picture (3) the car's **front is toward the left of the image and its rear toward the right**.

## Opening it from an ordinary app

The class is `android.hardware.AVMCamera`, a hidden API:

1. Lift the hidden-API restrictions for the process (the usual `VMRuntime.setHiddenApiExemptions` call through
   reflection; see [research-method.md](research-method.md)).
2. `AVMCamera.open(2, 1)` (camera id 2, mode 1). `open(2, 0)` returns the same 7680 × 1300 feed.
3. Give it a `SurfaceTexture` with `setTexture(surfaceTexture, 0)` and call `startPreview()`. Frames arrive at
   about **30 per second**. Close with `stopPreview()` and `close()`.

The only permission needed is the runtime permission **`CAMERA`**; nothing signature-level, no shell identity.
`isOpen()`, `getPreviewWidth()` and `getPreviewHeight()` answer as expected.

Two ways that did **not** give pictures: `addPreviewSurface()` with an `ImageReader` surface produced no frames
(the GL route through a `SurfaceTexture` works), and `onFrameAvailable` callbacks for the car's own rendering
engine's output did not arrive (see [image-app.md](image-app.md)); polling the texture's timestamp after
`updateTexImage()` does work.

To show one picture, draw the `SurfaceTexture` as an external OES texture and sample only its quarter of the
width: picture *n* (1 to 4) is the crop `x = (n − 1) / 4`, width `1 / 4`, the full height. The texture's own
transform matrix has to be applied to the coordinates (the picture is upside down otherwise).

## The car's own camera calibration

The *Image* app ships nominal calibration files for the 360° cameras in its APK (per model family, a 1300P set for
these 1920 × 1300 pictures). They give, for each of the four cameras:

- the **lens model**: `EQUIDISTANT`, with focal lengths `fx`, `fy` about 444–446 pixels per radian, the centre
  `(cu, cv)` about (960, 651) and four distortion terms `kc2`…`kc5` (about 0.157, −0.077, 0.024, −0.003), used as
  `θ_d = θ · (1 + kc2·θ² + kc3·θ⁴ + kc4·θ⁶ + kc5·θ⁸)`, then `x = cu + fx·θ_d·cos φ`, `y = cv + fy·θ_d·sin φ`;
- the **pose on the car**: a Rodrigues rotation sensor → body and the position of the camera in the body frame
  (x front, y left, z up). For the right mirror camera: position (1.91, −1.06, 1.28) m, optical axis 45° down and
  straight out to the right, picture-right toward the rear;
- the car: length 4.887 m, width 1.932 m (2.162 m with the mirrors).

These are the starting values; the factory refines them per car and stores the result where the shell cannot read
it, but the nominal ones were close enough to reproduce the car's own picture. Using them (and not a guessed lens)
is what makes a rebuilt view look right; a guessed lens (a plain `r = f·θ` with `f` = 531) had to be compensated by
other numbers and gave bent lines.

## Rebuilding the car's own side view

The *Image* app's right-side view (the pane named `right_2d`, 1412 × 1272 on a 2560 × 1440 screen, 1104 × 990 in
its own units) is a **view from a virtual camera that sits at the real lens position**, not a ground projection. Its
configuration file (`LeftRightViewParam`) gives that virtual camera:

| Value | Right view (left view mirrored) |
| --- | --- |
| Canvas | 1104 × 990 |
| Model | unified (Mei) camera with `ξ` = 0.4, focal length 630 |
| Principal point | (190, 420) right, (910, 420) left: far off-centre, which is why the car's side is only a thin strip at the picture's edge |
| Yaw, pitch, roll | −5°, 36°, 0° right (5°, 36°, 0° left); pitch is down |

The numbers as written in the file did not reproduce the car's picture on their own (match 0.53); letting a small
search adjust them against a capture of the car's own picture gave a close copy (match 0.88): **focal length about
900, `ξ` about 0.52, principal point (180, 383), yaw −5.5°, pitch 34.9°, roll 0.8°**. The yaw, pitch, roll and
principal point stayed close to the file; the focal length did not (the file's value is probably for another canvas
scale). With the camera pose and lens above, an output pixel is turned into a ray with the unified model's inverse
(`λ = (ξ + √(1 + (1−ξ²)·r²)) / (1 + r²)`, ray `(λ·mx, λ·my, λ − ξ)`), rotated into the body frame, then into the
sensor frame, and looked up in the fisheye picture with the lens model above. The result drawn live on the head unit
shows the car's side down the left edge, the ground ahead with its painted stripe and a neighbouring car, and the
far cars along the top, as the car's own view does. The left view uses the left camera's calibration with the yaw
and roll negated and the principal point mirrored; it looked right (the car's side on the right edge) but was not
compared with the car's own left view.

**The car's own pictures are not processed beyond a gain.** Its shader multiplies the colours by a fixed
`light_up_val` of **1.23** (from its `avm_param.yaml`) and clamps; there is no sharpening, no noise reduction and no
contrast curve. The views themselves come from lookup tables generated from the calibration and loaded at start
(`LoadLutFile`, `3d_srv_lut.bin`, `LUT_Generator`), drawn as a mesh with the camera picture as a texture.

### Extra processing was tried and not used

Three cheap passes on the graphics chip were tried on top of the 1.23 gain: a blend with the previous result where
the picture did not change (it removes most of the grain from a parked car and lets moving parts through), a small
sharpening plus a larger-radius local-contrast term, and a little more colour and contrast. They ran at the
camera's 30 frames a second at 1104 × 990. Compared in the **same screenshot** as the car's own pane (see
[research-method.md](research-method.md)) the plain rebuild and the car's own picture look alike; mild processing gives
a slightly cleaner ground, strong processing (sharpening 2.2, local contrast 0.9) shows the concrete's texture but
adds noise and halos around dark edges such as the neighbouring car. None of it was clearly better than the car's own
picture, so the **plain version (gain 1.23 only) was chosen**, to look like the car's own.

### Earlier attempts

Two simpler ways of making a "normal" view before the calibration was found are kept here because they explain the
numbers: a **look-direction view** (an equidistant lens with a guessed `f` = 531 and a yaw, pitch and roll chosen by
eye; level and clean but looks like a dashcam and the car's own wing fills half the picture), and an **empirical
ground-plane fit** (a virtual camera and a lens with eleven free numbers fitted to the car's picture, match 0.94 on
the ground area only; it looked close but bowed the painted stripe and fell apart outside the fitted frame). The
physical model above replaces both.

**Limits:**
- The calibration is the nominal one from the *Image* app's files, not this car's refined one.
- The right-side view's numbers were fitted against one scene; the left view's were not fitted at all.
- Only the ground is exact; standing things (cars, pillars) are stretched the way the car's own view stretches them.

## Reading the turn signal

The Light device, `android.hardware.bydauto.light.BYDAutoLightDevice`, answers `getTurnLightState()` and sends
changes to `onTurnLightStateChanged` (feature `LIGHT_TURN_SIGNAL_LIGHT`, id 950009900 = `0x38A0002C`; the switch
state is id 321912876). These were read with the shell-identity probes. `BYDAUTO_LIGHT_COMMON` is a normal
permission that an app can declare, but reading the turn signal **from an ordinary app was not tested**: on the
panorama and ADAS devices an ordinary app is refused even with the matching `…_COMMON` permission
([cluster-overlay.md](cluster-overlay.md#why-it-needs-the-shell-identity)).

Observed values (left stalk, right stalk, parked): **1 = off, 2 and 3 = left on, 4 and 5 = right on**; the two
values per side are probably the lit and dark phases of the flash. The car reports `sys.car.protocol=CANFD`; the
*Image* app's own code uses `1` off, `3` left and `5` right on that protocol, which agrees.

## Next to the car's own turn-signal window

The *Image* app's turn-signal feature ([image-app.md](image-app.md)) opens its own **"Right Rear"** window on the
centre screen when the right signal is on (top left, about 720 × 450 px on the 2560 × 1440 screen, with a close ×
and a "<>" arrow that switches the picture). Tested with that window up:

- **Our own drawing of the raw mirror picture kept running at 30 frames a second** in a box at the bottom of the
  centre screen; the car's window sat at the top and both showed live pictures. Opening the 360° feed while the
  car's own window had it open caused no error.
- **The *Image* engine's picture drawn into our own box froze** as soon as the car's window appeared (the pixels of
  successive screenshots became identical; before that they changed by 2–7 on average). The car's window wins the
  engine and ours keeps its last frame. Repeated twice.

So an app that wants to show a view **next to** the car's own turn-signal window has to draw it itself from the
raw 360° feed; the engine's calibrated views are not available at the same time.

## One picture per camera index (tested)

The 360 camera does not have to be read as one 7680 x 1300 strip. A third-party app's code (read, not run) opens the
same `android.hardware.AVMCamera` and binds **one surface per index** with `addPreviewSurface(surface, index)` plus
`setPreviewSurface(surface, index)` before `startPreview()`, index **2** for its left window and **3** for its right
window; it finds the camera id with `AVMCamera.getCameraId(name)` for `pano_h`, `pano_l`, `apa`, `byd_apa` in that
order. Tried here with the lab app (parked; `open(2, 1)`, one `SurfaceTexture` per index, all eight indexes, and the
frames compared pixel by pixel):

| Index | What the surface receives | Frame rate |
| --- | --- | --- |
| 0 | the whole strip, rear, left, right, front side by side | 30 a second |
| 1 | the rear picture alone | 30 |
| 2 | the left mirror picture alone | 30 |
| 3 | the right mirror picture alone | 30 |
| 4 | the front picture alone | 30 |
| 5, 6, 7 | a 2 x 2 composite of the four pictures, each in a different arrangement (5: rear, left / right, front; 6: left, rear / front, right; 7: right, front / rear, left) | 30 |

All indexes can be bound at the same time and every one delivers every frame. `addPreviewSurface` answered `true`
for all eight.

- The single pictures are the strip's pictures (same-time difference to the matching strip column about 10 grey levels
  on a dark night scene, the same size as one picture differs from itself a frame later; a wrong column differs by
  29 to 41).
- **They are darker.** On average the single pictures are 15 to 25 % darker than the same picture cut out of the
  strip (mean over the picture: 40.5 / 27 / 29 against 50 / 38 / 41 for the rear one, 36.6 / 36.9 / 35.3 against
  47.4 / 47.6 / 46.1 for the left, 48.9 / 47 / 46.1 against 57.6 / 55.9 / 55.3 for the right). It is not one constant gain:
  by pixel value the strip lifts the shadows (about 8 → 23, 24 → 36, 40 → 50), matches in the middle (about 100) and is
  slightly lower in the highlights (232 → 215); some of that is grain noise averaging towards the middle. So the strip
  looks like the single pictures with a tone curve on top.
- The size of the single pictures was not read; they fill their own surface as one fisheye picture without the strip's
  neighbours, with the same field of view as the strip's column.
- Which of the two the car's own side view starts from was not determined, so the gain of 1.23 used for the rebuilt side
  view ([above](#rebuilding-the-cars-own-side-view)) belongs to the strip's pictures and would need to be fitted again
  for single pictures.

Other things that app does: it draws its pictures in a plain `TextureView` with only a 2D matrix (scale, rotate 90
degrees), so it does no dewarping; it reads the speed and the gear, shows nothing below a speed threshold (20 km/h by
default) and hides 10 s after the signal is off; for the cluster it uses the `auto_container` commands, which this car
refuses to the shell ([cluster-overlay.md](cluster-overlay.md#how-another-app-puts-things-on-the-cluster-read-from-its-code-not-tried-here)).

## Not verified

- The left side view against the car's own left view (it was drawn mirrored from the right one and looked right, but
  was not compared), and either side with this car's refined calibration instead of the nominal one.
- The rebuilt side view (with the quality passes) in a window on the cluster. An earlier look-direction view was drawn
  there and the driver judged it smooth ([cluster-overlay.md](cluster-overlay.md#a-live-camera-picture-in-the-window)).
- Any of it while driving. The picture and window tests were parked; the rendering-service and first cluster tests
  were also repeated in D at 0 km/h with the brake held.
- Night pictures: the first channel snapshots were taken at night and were too dark to tell the pictures apart;
  the identification used daylight.
