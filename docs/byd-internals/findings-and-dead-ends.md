# Quirks, and the things that did not work

Written down so nobody spends the same hours again. All of it was observed on one vehicle (see
[README](README.md)).

## Quirks

**The four pictures of the 360° feed are not in the usual order.** Left to right they are rear, left mirror, right
mirror, front. See [blind-spot-cameras.md](blind-spot-cameras.md#the-360-feed).

**The 360° feed has no second mode.** `AVMCamera.open(2, 0)` and `open(2, 1)` both give 7680 × 1300; the car's own
blind-spot window opens it with `0`, its panorama screens with `1`.

**The camera listings disagree.** The camera-info class names five types (ids 2, 1, 0, 11001, 11001), `dumpsys
media.camera` shows three devices (0, 1, 2) and an app's `camera2` listing showed 1, 2 and 11001. None of them
names a side or blind-spot camera.

**A write-only id reads as junk.** `ADAS_AVM_VIDEO_STREAMING_DISPLAY_STATUS_SET` answers a `get` with −10011 (or
−2147482648); only the matching state, if any, can be read back, so the write cannot be verified by reading. The
panorama projection flag (1309671486) does read back.

**The car's engine view freezes, it does not go blank.** When the car's own window takes the rendering engine, an
app's surface keeps its last frame (the pixels of successive screenshots become identical), and the engine's
callbacks stop. An app that must know has to compare frames or watch the texture's timestamp.

**`screencap` shows overlay windows on the centre screen**, including the car's "Right Rear" window, so a window on
the centre screen can be checked from the shell by capturing it. A volume or brightness panel that the car shows by
itself can cover the capture.

**The cluster window's `addView` succeeds either way.** A window on the cluster display is listed by `dumpsys
window` whether or not the cluster shows it, so the listing proves nothing about what the driver sees.

**The `fission_` display's id is not fixed.** It was 3 here; find it by name.

**A view re-aimed from the lens with a guessed lens does not look like the car's.** Looking along the car's side with a
plain rotation and an equidistant lens of invented focal length fills half the picture with the car's own wing and
bends straight lines; fitting a ground-plane model compensated for it but fell apart outside the fitted frame. The
car's own side view is a virtual camera sitting at the real lens with an off-centre principal point; with the real
calibration it can be rebuilt closely
([blind-spot-cameras.md](blind-spot-cameras.md#rebuilding-the-cars-own-side-view)).

**Where the rear-facing side picture comes from is not settled.** The car's owner reports that the rear-facing
blind-spot views come from dedicated cameras and that only the front-facing side pictures are part of the 360° feed.
The *Image* app's code opens camera id 2 (the 360° feed) for its "Right Rear" window, and no other camera type or
device that could be one was found, so the rear-facing picture is unreachable here either way. This research only
used the front-facing side pictures.

## Things that did not work

**A window on the cluster without the projection flag.** Accepted, listed, visible to `dumpsys`, blank on the
cluster, in P and in D at 0 km/h. See [cluster-overlay.md](cluster-overlay.md).

**A window beside the car's own cluster window.** The cluster shows only the 686 × 386 rectangle at (36, 208) of its
display, so a window anywhere else is accepted and drawn but never seen (the area shows black where nothing is drawn
in it). See [cluster-overlay.md](cluster-overlay.md#only-one-rectangle-of-the-display-is-shown).

**The cluster's layout controls.** Writing `INSTRUMENT_CENTER_PROJECTION_SET` (2, 7, 4), `INSTRUMENT_RIGHT_PROJECTION_SET`
(1) and `INSTRUMENT_LEFT_PROJECTION_SET` (2) with a grid window on display 3, 4 or 5, with and without the projection
flag, never showed anything beyond the one 686 × 386 rectangle. See
[cluster-overlay.md](cluster-overlay.md#trying-to-make-the-cluster-show-more-of-the-display).

**Reading the cluster with `screencap -d 3`.** Returns an empty file; the cluster's virtual display cannot be
captured from the shell, so what it shows has to be looked at.

**Writing the projection or ADAS flag from an ordinary app.** `SecurityException: [setInt] permission deny!`; the
`BYDAUTO_PANORAMA_*` and `BYDAUTO_ADAS_*` GET/SET permissions are signature level and `pm grant` is refused. The normal
`BYDAUTO_PANORAMA_COMMON` is granted but does not help.

**`BYDAutoPanoramaDevice.getInstance()` from the shell.** Fails with a package/uid mismatch from its settings
provider query. The non-public constructor works.

**The blind-spot views through the Image app's rendering service.** Viewpoints 3203/3204/3205 (and the corner and
3D views) stay blank for a foreign client, in P, in D, and with the turn signal held on. Only 2000–2003 draw. See
[image-app.md](image-app.md#which-viewpoints-draw).

**Using the Image engine next to the car's own turn-signal window.** The car's window takes the engine; ours freezes.

**An `ImageReader` surface as the camera's preview target.** `addPreviewSurface` accepted it and no frames came.

**`onFrameAvailable` for the engine's output.** The callbacks never arrived on the output `SurfaceTexture`;
`updateTexImage()` and the texture's timestamp worked.

**Finding a separate blind-spot camera.** There is none in the camera types, in `camera2` or in `dumpsys`.

**The car's calibration and settings.** `/collect2/autovideo/` (the calibration for the engine's views and
`initSettingParam.json` with the turn-signal setting) cannot be read by the shell, so neither the calibrated view
nor the current setting can be copied or read.

**A box of a different shape than the car's 1104 × 990 canvas.** The rebuilt side view fills the canvas's shape; a wider
box (16 : 9) shows the same vertical extent with more at the sides, where the view was never compared with the car's
own and the picture starts to stretch.

**A "not touchable" overlay window is not fully opaque.** An overlay window with `FLAG_NOT_TOUCHABLE` showed the car's
own icon from the layer underneath through our picture (about a quarter of it), even with `alpha = 1` and an opaque
format; the system limits the opacity of overlays that cannot be touched. A touchable overlay (`FLAG_NOT_FOCUSABLE |
FLAG_NOT_TOUCH_MODAL`) is opaque.

**Our camera feed kept running when the *Image* app started.** With our own overlay drawing the right-mirror picture,
starting the car's full surround-view app, and its right-side view, did not stall our feed (30 frames a second before
and after) and did not freeze the car's own pane. (An earlier "freeze" seen on the screen was our own test app
stopping its drawing when its activity stepped back, not the car.)

**Night pictures.** The first snapshots of the four pictures were taken in the dark and could not be told apart;
daylight in a lit car park was enough to match them against the car's own right-side view.
