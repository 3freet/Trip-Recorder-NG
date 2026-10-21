# A window on the instrument cluster

How the car's own *Image* app puts its blind-spot window on the instrument cluster, what an ordinary app has to
do to get a window of its own there in normal gauge mode, and why the last step needs the ADB shell identity.
Observed on one vehicle, parked (see [README](README.md)).

This is a **different route** from the one in the sister project's
[cluster-screen.md](https://github.com/3freet/BYD-Assistant-NG/blob/main/docs/byd-internals/cluster-screen.md): that
one switches the navigation view to "on the cluster" (navigation type 4, BYD Maps' meter activity) and the app shows
in the middle where the map is. This one leaves the gauges as they are and adds a small window, the way the *Image*
app does for its turn-signal view.

## The display

The cluster's virtual display is published by `com.xdja.containerservice`: **1920 × 720**, named
`fission_bg_XDJAScreenProjection` (display id 3 on this unit; look it up by the `fission_` name prefix, not by id),
flags `PRESENTATION | OWN_CONTENT_ONLY`. Displays 4 and 5 (`shared_fission_*`) and display 2 (the passenger
screen, `fse`) are other displays.

## What the Image app does

`PIP2MeterAlert` makes its window like this:

```
Context c = createDisplayContext(display).createWindowContext(2038, null);   // TYPE_APPLICATION_OVERLAY
WindowManager.LayoutParams: type 2038, format 1, gravity 51 (top left), flags 40,
                            x = 36, y = 208, 686 × 386
```

with a `SurfaceView` inside that the rendering engine draws into ([image-app.md](image-app.md)).

## What an ordinary app can do

A test app with the *display over other apps* permission (`appops set <pkg> SYSTEM_ALERT_WINDOW allow` from the
shell; in a real app the user grants it in Settings) made the same window with a plain orange `TextView` and a
counter:

- `addView` was **accepted**: `dumpsys window` listed it on display 3 as `APPLICATION_OVERLAY`, visible, at
  (36, 208), 686 × 386, and it was removed cleanly afterwards;
- but **the physical cluster showed nothing**, in P and in D at 0 km/h with the brake held.

## Only one rectangle of the display is shown

With the flag on, the cluster does **not** show the whole 1920 × 720 display. A labelled grid drawn over the whole
display (photographed on the cluster) showed only the rectangle **x 36 to 722, y 208 to 594 (686 × 386)**, with rounded
corners: the area where the *Image* app's own cluster window goes. The grid lines, the numbers and the background were
visible only inside it, and the gauges and warning lights around it are the cluster's own. A window placed anywhere
else on the display (one was put at x 740, 560 wide, beside the car's own area) is accepted, listed, "visible" and drawn
at 30 frames a second, and **never appears**; what the driver sees there is the empty background of the area, black.
Anything meant for the cluster has to be drawn inside that rectangle, so it cannot sit *beside* the car's own
window, only over it or over a part of it.

## Trying to make the cluster show more of the display

The question was whether any switch makes the cluster show more than that rectangle (the right half of the cluster, the
middle). The car's own code has three layout controls on the **Instrument** device, the ones its projection service
(`com.example.amapservice`, `BydProjectionService`, a priv-app whose exported service only answers whitelisted caller
packages, so an ordinary app cannot use it) writes when it moves content between the cluster's areas:

| Control | Id | Value at rest |
| --- | --- | --- |
| `INSTRUMENT_CENTER_PROJECTION_SET` | 1086373904 | 0 |
| `INSTRUMENT_LEFT_PROJECTION_SET` | 1086373907 | 0 |
| `INSTRUMENT_RIGHT_PROJECTION_SET` | 1086373910 | 0 |

From the decompiled service: centre and full content are drawn on display 4 (`shared_fission_bg_XDJAScreenProjection_0`),
the cards on display 5 (`shared_fission_bg_XDJAScreenProjection_1`). The ids can be read and written from the shell
helper (`get`/`set` on the Instrument device) and answer 0 (accepted).

Tested parked, one thing at a time, with a labelled grid window on the display named and the control put back to 0
afterwards (the cluster was watched by a person; the grid carried a label saying which setting was on):

| Grid on | Control written | Cluster flag | Result |
| --- | --- | --- | --- |
| display 4 | centre = 2, 7, 4 | off | nothing |
| display 5 | right = 1, then left = 2 | off | nothing |
| display 3 | centre = 2, 7, 4 | off | nothing |
| display 3 | centre = 0 (baseline) | **on** | the grid inside the usual 686 × 386 rectangle only |
| display 3 | centre = 2, 7 | **on** | the same rectangle, nothing more |

So on this car, in normal gauge mode, none of the three controls made anything of ours appear or widened the visible
area; **the one rectangle is all an ordinary app's window can use.** (The controls' effect on the car's own content was
not looked at: only whether our windows appeared.) The other route that gives an app more of the cluster is the
navigation view on the cluster of the sister project's
[cluster-screen.md](https://github.com/3freet/BYD-Assistant-NG/blob/main/docs/byd-internals/cluster-screen.md), which
replaces the middle of the gauges with the app's picture; it was not tried for the blind-spot window.

## How another app puts things on the cluster (read from its code, not tried here)

A third-party car-control app's code was read (never run) to see how it fills and divides the cluster. It does
**not** use the projection flag above or the layout controls above; it uses four other things, all as the ADB shell
identity:

1. **The car's `auto_container` service** (`android.os.IAutoContainer`, service 64 on this car, `service check
   auto_container` answers "found"). One command in, `service call auto_container 2 i32 1000 i32 <cmd> s16 ""`:
   **16** to take the cluster over, **18** to give it back, then about a second later **0**. The reply is a `Parcel(`
   whose first word is `00000000` on success. It sends 16 whenever a view is going to the cluster, and only those
   three numbers (they are whitelisted in its code).
2. **Its own layer in the cluster display's layer stack.** The helper builds a `SurfaceControl` layer directly in the
   layer stack whose id is the cluster display's (3 here), at (0, 0), the display's whole size (1920 × 720), z
   1,000,000, opaque and hidden; a *virtual display* (named `…cluster_app_…`, flags 1033, falling back to 9 and 3,
   240 dpi) renders into that layer, an app is started on that virtual display (`am start --display <id>`), and the
   layer is shown once the first frame has arrived. No window of the window manager is involved.
3. **Plain overlay windows** (`TYPE_APPLICATION_OVERLAY`, 2038) on the cluster display for its own blind-spot view,
   sized and placed by percentages of the 1920 × 720 display (default: the whole display, centred; the user can
   change width, height and offset).
4. **The car's own cluster views**, started on the shared displays: `am start-activity --display <id of
   shared_fission_bg_…_0> -a <action> -n <component>` with `com.byd.automap.START_MAP_VIEW` →
   `…meter.MeterActivity` (full map), `START_MINI_MAP_CARD` → `MeterTbtActivity` (turn card) and
   `START_HALF_MAP_CARD` → `MeterSmallScreenActivity` (half-map card), the cards on both `_0` and `_1`, in the map
   app's package (`com.byd.launchermap` in that app, `com.byd.naviauto` in the sister project), and
   `com.byd.sr.cluster_presentation` → `com.byd.sr/…ClusterActivity`. This is the "sections" the car itself
   divides the cluster into: the centre/full content on `_0`, the cards on `_1`.

The sequence it uses to take the cluster over is **30**, a 6 s wait, **16** (must be accepted), another 6 s wait, then
**35**; to give it back, **18**, 1 s, **0**. **On this car it does not work from the shell.** Sending 16 on its own, and
then the whole sequence 30, 16, 35, 18, 0 with the labelled grid on display 3, every call was answered `Parcel(00000000
ffffffff)`: the call went through, the result was -1, and the other app counts only an all-zero result as accepted.
The car's log names the reason: `xdja_AutoContainerService: checkSendPermissionAndAllowType: selfUid = 1000,
callingUid = 2000` (the shell), and the service's allow-list, `/system/etc/container_comm_cfg.json` (world-readable),
permits exactly two callers:

| Direction | Package | Types allowed |
| --- | --- | --- |
| `ivi_to_cluster` and `cluster_to_ivi` | `com.xdja.clusterdemo` (not installed here) | 0 to 134217727 |
| `ivi_to_cluster` and `cluster_to_ivi` | `com.byd.multipletheme` (the car's theme store, installed) | 8 only |

The shell and an ordinary app are not on it, so on this car (and this firmware) the `auto_container` route is closed to
us. The other app probably works on cars whose list differs. Nothing in it can be reached without being one of those
two packages, and impersonating one was not tried.

What it does not show is how much of the display the cluster takes while command 16 is active; its blind-spot window
is a whole-display window by default, which suggests the whole 1920 × 720 is used in that state, but this was **not
tried on the car**, and whether 16 also gives up the gauges is not known.

## The cluster's own sections, and who may fill them

The car's projection service (`com.example.amapservice`, `BydProjectionService`, read from its code) divides the cluster into
three **slots** and gives each a fixed owner, identified by the caller's package name (`getNameForUid`; the shell and
ordinary apps are none of them, so every call from them is refused with "has no permission"):

| Slot (`ScreenPosition`) | Content (`ContentType`) | Owner on a production car |
| --- | --- | --- |
| `CLUSTER_FULL`, the bottom area | map view, ads view, night vision, scene mode | `com.byd.naviauto` / `com.byd.launchermap`, `com.byd.sr`, `com.byd.cipService`, `com.byd.scenemodes` |
| `CLUSTER_LEFT`, top left card | picture-in-picture card, mini-map card | `com.byd.avc` (the Image app) |
| `CLUSTER_RIGHT`, top right card | alarm card (driving-assist alerts), turn card, mini-map card | `com.byd.sr`; the map package only when the cluster supports a top-left mini map |

So the **empty space on the right is the top-right card slot, reserved for the driving-assist alarm card**. In the
production build the activity for it is the empty string (`BYD_TOP_RIGHT_ADS_ALARM_ACTIVITY_NAME = ""`), and `com.byd.sr`
is not installed on this car, so nothing ever fills it. The slot is driven through `INSTRUMENT_RIGHT_PROJECTION_SET`
(written by the service, not by the content); writing that control by hand with a window on display 5 showed nothing
(see above), because the cluster takes the picture from what the service starts on the shared display, not from the
control alone.

The service has a **developer test mode**, switched on by the system property `persist.debug.cluster.projection=1`: it
replaces every owner with a demo package name (`com.byd.cluster.projection.adsdemo` for the alarm card on the right,
`pipdemo` for the left card, `mapdemo`, `nightvisiondemo`, `scenemodedemo`). It was **not tried**: it is a system setting
that also stops the real owners (the Image app's own cluster window, the map) from being recognised while it is on,
and using it would mean installing an app under a vendor demo package name.

## The flag that makes it show

With the same window up, writing the *Image* app's own flag made the window appear on the cluster at once:

| Device | Id | Meaning | Value |
| --- | --- | --- | --- |
| Panorama | `PANORAMA_SCREEN_PROJECTION_STATUS_IVI_TO_INS_SET`, **1309671486** | "project the head unit's picture to the instrument" | **1** on, **2** off; readable; read 2 at rest |
| Panorama | `PANORAMA_INSTRUMENT_AVAILABLE_STATUS`, 1086337094 | the instrument can take it | read 1 on this car |
| ADAS | `ADAS_AVM_VIDEO_STREAMING_DISPLAY_STATUS_SET`, 850452533 | camera streaming state | 0 default, 1 normal, 2 error; write-only (reading returns −10011) |

Tested **in P with the window up**:

- ADAS streaming = 1, then projection = 1: the orange box appeared on the cluster;
- projection = 1 **alone**: the orange box appeared. So the projection flag is the one that matters; the ADAS
  flag is not needed for the window to show.
- Setting projection back to 2 and streaming to 0 afterwards restored the resting values.

The *Image* app writes projection 1 when its window has drawn its first frame and 2 when the window is hidden, which
is about two seconds after the signal goes off; an app that wants its window to stay has to write the flag itself
for as long as the window is up.

## A live camera picture in the window

The window can carry a **live camera picture**, not only a still: a `SurfaceView` was put inside the cluster window
(686 × 386 at (36, 208), as above) and the lab app's own OpenGL loop drew a flattened right-mirror picture (the first, look-direction version)
([blind-spot-cameras.md](blind-spot-cameras.md#rebuilding-the-cars-own-side-view)) into its surface, straight from the
360° feed. The flow was: `AVMCamera(2, 1)` opened by the app, the window added on the `fission_` display, the
projection flag written 6 seconds later by the shell helper and written back to 2 about 22 seconds after that.
The driver looking at the cluster reported the picture appeared and was **smooth**. The camera and the drawing
needed no permission beyond `CAMERA`; only the flag needed the shell identity.

## Why it needs the shell identity

From inside an ordinary app (`CAMERA`, `SYSTEM_ALERT_WINDOW`, the normal `BYDAUTO_PANORAMA_COMMON` permission
granted, and `BYDAUTO_PANORAMA_SET/GET` and `BYDAUTO_ADAS_SET/GET` requested) both devices were constructed
without error, but every read and write of these ids failed with
`SecurityException: [getInt] permission deny!` / `[setInt] permission deny!`.

`BYDAUTO_PANORAMA_GET`, `BYDAUTO_PANORAMA_SET`, `BYDAUTO_ADAS_GET` and `BYDAUTO_ADAS_SET` have protection level
**signature**, and `pm grant` of one of them is refused. Only the ADB shell identity (the helper process the
sister project and this app's *Vehicle data link* start over loopback ADB) is accepted. So **a cluster window needs
a helper; the same window on the centre screen does not.**

Writing the ids from a shell-run helper (`app_process`, reflection, see [research-method.md](research-method.md)):

- the ADAS device: `BYDAutoADASDevice.getInstance(context)` works from the shell, then
  `set(new int[]{id}, eventValueWithIntValue)`;
- the panorama device: `BYDAutoPanoramaDevice.getInstance(context)` **fails** from the shell, because its first
  call queries the car's settings provider with the calling package `android`, which does not match uid 2000
  (`SecurityException: Given calling package android does not match caller's uid 2000`; using a context of
  `com.android.shell` did not help). Constructing it directly through its non-public constructor
  (`getDeclaredConstructor(Context.class)` with `setAccessible(true)`) works, and then `get(int[], Integer.TYPE)`
  and `set(int[], BYDAutoEventValue)` answer normally.

## Not verified

- The fitted ground view in the cluster window (the live test drew the simpler look-direction view), and how
  sharp the picture is compared with the centre screen (only "smooth" was reported).
- A window that covers only part of the visible rectangle, with the car's own window showing in the rest.
- The flag and the window while driving; the successful flag test was parked, in P.
- Whether a window of ours can stay while the *Image* app's own cluster window opens and closes at the same time
  (the *Image* app writes projection 2 when its window hides).
- The `startContentProjection()` route the app uses on other models.
