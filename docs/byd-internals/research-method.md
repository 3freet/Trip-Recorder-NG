# How the tests were run

Short notes on the method, so the same checks can be repeated on another car. The vehicle API, the probes and the
helper process are explained in the sister project's
[research-method.md](https://github.com/3freet/BYD-Assistant-NG/blob/main/docs/byd-internals/research-method.md);
this page only adds what was needed for the cameras and the cluster window.

## Parked first

Every test that showed a camera, drew a window or wrote a vehicle state was run **parked**. Gear, brake and speed
were read just before with the read-only probes (`Getters` from the sister project):

```
Getters android.hardware.bydauto.gearbox.BYDAutoGearboxDevice 2   # getGearboxAutoModeType(): 1 = P, 4 = D
                                                                  # getBrakePedalState():     1 pressed, 2 released
Getters android.hardware.bydauto.speed.BYDAutoSpeedDevice 1       # getCurrentSpeed(): km/h
```

A few tests (the rendering service, the first cluster window) were repeated in D with the brake held and a speed of
0. Nothing was ever run with the car moving, and a window that covers part of the cluster should not be shown to a
driver who is moving until it has been looked at in the car.

## A throw-away test app

An ordinary app, signed with its own key, whose manifest asked for `CAMERA`, `SYSTEM_ALERT_WINDOW`,
`BYDAUTO_PANORAMA_COMMON` and `BYDAUTO_VIDEO_COMMON` (and, to prove they are refused, the signature-level
`BYDAUTO_PANORAMA_SET/GET` and `BYDAUTO_ADAS_SET/GET`). Set up from the shell:

```
adb install -r Lab.apk
pm grant <pkg> android.permission.CAMERA
appops set <pkg> SYSTEM_ALERT_WINDOW allow
```

The test modes were chosen with intent extras (`am start -n <pkg>/.MainActivity --ez cluster true`, `--ei ch 3`,
and so on) and the app wrote a log to its external files directory, which was pulled with `adb pull` afterwards.

**Hidden API.** `AVMCamera` is a hidden class, so the app lifts the restriction first, through reflection on
`dalvik.system.VMRuntime`:

```java
Method forName = Class.class.getDeclaredMethod("forName", String.class);
Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
Class<?> vm = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
Object runtime = ((Method) getDeclaredMethod.invoke(vm, "getRuntime", null)).invoke(null);
((Method) getDeclaredMethod.invoke(vm, "setHiddenApiExemptions", new Class[]{String[].class})).invoke(runtime, (Object) new String[]{"L"});
```

## A shell helper

Anything that needs the shell identity ran as a tiny class in a dex file, started with `app_process`:

```
adb push helper.dex /data/local/tmp/helper.dex
adb shell "CLASSPATH=/data/local/tmp/helper.dex app_process /system/bin Helper read"
```

It gets a context from `ActivityThread.systemMain().getSystemContext()`, builds the device class by reflection and
calls `get(int[], Integer.TYPE)` and `set(int[], BYDAutoEventValue)`. It always **read first, wrote one value, read
back and restored** the resting value afterwards. The camera-type lister reads `BmmCameraInfo` the same way.

## Judging pictures

- **Which picture is which:** a daylight snapshot of each of the four pictures, set against the car's own
  right-side view (the engine view, which shows the right side with the same neighbouring cars) and confirmed by
  the person in the car.
- **Live or frozen:** the shell captures the centre screen every three seconds (`adb exec-out screencap -p`) and
  the mean absolute colour difference of a fixed region between consecutive captures is computed. Live pictures
  differed by 1.7–9 on a 0–255 scale (camera noise included); a frozen picture by exactly 0.00.
- **Flattening:** the equidistant model was tried first offline on a saved picture (a small Java program writing a
  1280 × 720 view for given centre, `f`, yaw, pitch, roll and field of view), four variants at a time on one
  sheet, then ported to a fragment shader and checked live on the car.
- **Reading the app's own configuration:** the Image app's APK was unpacked on the Mac and its calibration and
  parameter files read (the `assets/resource_byd` folder); the native library was searched with `strings` for
  names (views, files, uniforms). Those gave the lens model, the camera poses and the side view's virtual camera.
- **Matching the car's own view:** the car's right-side view was captured from the centre screen while it was
  showing, and the fisheye picture from the 360° feed (the scene was parked and static). A small Java program
  rendered the fisheye picture through a parametrised ground-plane model and a random hill-climb (coordinate nudges
  that shrink over time, several random starts in parallel) maximised the normalised cross-correlation with the
  car's picture over the ground area, with the far cars and the text lines masked out. It runs about 15 000
  renders a second at 140 × 126 pixels, so a full search takes a minute. No libraries were used.
- **A fair side-by-side with the car's own picture:** our picture is drawn in an overlay window (`TYPE_APPLICATION_OVERLAY`)
  placed over the car's left pane while the car's right pane stays live underneath, and one `screencap` captures both;
  the two are then cut out of that one screenshot and put next to each other. The overlay has to be made by a
  **touchable** window: a window with `FLAG_NOT_TOUCHABLE` has its opacity capped by the system, which let the layer
  underneath (the car's top-down icon) ghost through our picture. Whether each picture is live or frozen is checked by
  taking a screenshot every second or so and measuring the mean colour difference of a fixed region between
  neighbours (a live picture changes by 2–4 on a 0–255 scale, a frozen one by exactly 0.00).
- **The cluster:** only by looking at it; `screencap -d 3` returns an empty file. Each test was run with the
  person in the car watching the cluster and saying what it showed.

## Cleaning up

The test app is uninstalled from the car and its files (`/data/local/tmp/*.dex`, the log and picture files in its
external directory) are removed; the `appops` entry goes with the uninstall. Pictures taken in a car park show other
people's cars and plates, so they stay out of the repository.
