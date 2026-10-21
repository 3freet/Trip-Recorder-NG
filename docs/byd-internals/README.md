# BYD DiLink internals — cameras and the cluster window

These notes record what was found out about the **blind-spot and 360° cameras** and about **putting a window on
the instrument cluster** of a BYD DiLink head unit, from an ordinary Android app and without root. They were
written while working out a possible "show the side cameras when a turn signal is on" option for Trip Recorder NG,
and they are kept so that anyone working on a BYD head unit does not have to rediscover them.

> **Read this first.**
> Everything here was **observed** on one vehicle and one firmware: a plug-in hybrid with a DiLink 5.1 head unit
> (`ro.product.model` reads `DiLink5.1`, Android 13). None of it is official or documented by BYD, none of it is
> guaranteed to hold on another model or after a software update, and some of it will be wrong for your car.
> Treat every id and value as a hypothesis until you have read it back on your own vehicle. Everything that
> shows a camera or writes a vehicle state was tried **while parked**; do not try it on a moving car, and accept
> that you do so at your own responsibility.

## Where this came from

The head unit itself: its framework classes (read with small probes), its camera and window services (`dumpsys`),
the code of the car's own *Image* app (`com.byd.avc`, decompiled from the APK on the same unit) to see what it
asks the system for, and a throw-away test app that tried the same calls from an ordinary app. **Nothing is
copied from another app's data or from leaked documents**; ids and values are what the car answered.

The vehicle API itself (device classes, permission model, the helper process started over ADB) is documented in
the sister project's notes, which these pages build on:
[BYD-Assistant-NG / docs / byd-internals](https://github.com/3freet/BYD-Assistant-NG/tree/main/docs/byd-internals)
(`hal-and-permissions.md`, `cluster-screen.md`, `research-method.md`).

## Contents

| Document | What it covers |
| --- | --- |
| [blind-spot-cameras.md](blind-spot-cameras.md) | The cameras the car has, the four-picture 360° feed and which picture is which, getting each picture on its own through the camera's surface indexes, opening it from an ordinary app, flattening a mirror camera into a normal view, reading the turn signal, and what happens next to the car's own turn-signal window |
| [image-app.md](image-app.md) | The *Image* app (`com.byd.avc`): its turn-signal setting and how an ordinary app can read it, when its own cluster window exists (gear D only), its windows, its exported rendering service, which views that service draws and which stay blank, and why it can serve only one window |
| [cluster-overlay.md](cluster-overlay.md) | Putting an ordinary app's window on the instrument cluster in normal gauge mode: the window, the one rectangle the cluster shows, the projection flag the car needs and why it needs the ADB shell identity, the layout controls that did not widen it, the cluster's own slots and who may fill them, and the `auto_container` service that refuses the shell |
| [findings-and-dead-ends.md](findings-and-dead-ends.md) | Quirks and everything that did not work, so nobody spends the same hours again |
| [research-method.md](research-method.md) | How the tests were run: the parked rule, the test app, the shell helper, reading gear and speed, comparing screenshots |

## What the research settled

| Question | Answer |
| --- | --- |
| Can the turn signal be read? | Not by an ordinary app (every Light-device read is denied). The shell helper reads the lamp output (`LIGHT_TURN_SIGNAL_LIGHT`, on for as long as the lamps are in use, three blinks of a flick included), with the stalk (`getTurnLightState()`) as a second source ([blind-spot-cameras.md](blind-spot-cameras.md#reading-the-turn-signal)). |
| Can an ordinary app open the 360° camera feed? | Yes, with the `CAMERA` permission only, through the hidden `android.hardware.AVMCamera` class. About 30 frames a second. |
| Is there a separate side or blind-spot camera it can open? | No. The car lists the 360° feed, the driver and in-cabin cameras and the rear camera. The rear-facing side picture is drawn by the *Image* app's engine; where its pixels come from is unresolved. |
| Can each of the four pictures be had on its own? | Yes: `addPreviewSurface(surface, index)` on the same camera gives index 0 the whole strip, 1 rear, 2 left, 3 right, 4 front and 5 to 7 a 2 x 2 composite, all at 30 frames a second at once. The single pictures are about 20 % darker than the strip's crops ([blind-spot-cameras.md](blind-spot-cameras.md#one-picture-per-camera-index-tested)). |
| Can the app draw a normal-looking view out of a mirror camera? | Yes, and it can match the *Image* app's own side view: the app's files hold the camera calibration and the side view's virtual camera, and rebuilding that gives a close copy, which can then be cleaned up and sharpened ([blind-spot-cameras.md](blind-spot-cameras.md#rebuilding-the-cars-own-side-view)). |
| Can the app ask the *Image* app's engine for its calibrated views next to the car's own window? | No. The engine serves one window; the car's own window wins and ours freezes. |
| Can an ordinary app put a window on the cluster? | The window is accepted, but nothing shows until a projection flag is set, and writing that flag needs the shell identity (a helper). With the flag set a live camera picture in the window was smooth. Only one 686 × 386 rectangle of the cluster is shown, and none of the car's layout controls widened it. |
| Can the empty top-right area of the cluster be used? | Not from an ordinary app. It is the driving-assist alarm card slot, owned by a vendor package that is not on this car; the projection service refuses every other caller, and the `auto_container` service the other route needs refuses the shell (`ffffffff`, its allow-list names two other packages). A developer test mode exists and was deliberately not tried ([cluster-overlay.md](cluster-overlay.md#the-clusters-own-sections-and-who-may-fill-them)). |
| When does the car's own window exist on the cluster, and can an app tell? | Only in gear D and only when its "Triggered by Turn Signal" setting is the floating window with the left signal on the instrument. The setting can be read, read-only, by asking the Image app's exported messenger service ([image-app.md](image-app.md#when-the-cars-window-exists-and-asking-for-the-setting)). |
