# AGENTS.md

## Build and install

- Build dependencies are the jars in `lib/`: `BaseMod.jar` (Steam workshop item 1605833019) and `desktop-1.0.jar` (the game, from `SlayTheSpire.app/Contents/Resources`). The pom references them as system-scope dependencies and does not bundle them. `lib/` is gitignored: each developer supplies the jars themselves, and they must be present to build. Replace them when the game or BaseMod updates.
- `mvn -q package` produces `target/BorderlessFullscreen.jar` (JNA is not bundled; the mod uses the copy inside the game jar).
- Install by copying the jar into `~/Library/Application Support/Steam/steamapps/common/SlayTheSpire/SlayTheSpire.app/Contents/Resources/mods/`, then restart the game. There are no tests or linters.
- Source level is Java 8 (`maven.compiler.release` 8) because the game ships a Java 8 JRE. No lambdas needed, but keep to Java 8 APIs.
- `src/main/java/com/evacipated/.../SpireInitializer.java` is a compile-only stub; the jar excludes it and ModTheSpire supplies the real one.

## Architecture

macOS-only ModTheSpire mod that gives Slay the Spire borderless fullscreen by driving AppKit directly through JNA (no native code of our own).

- `SettingsPanel` registers a BaseMod mod-settings entry (the mod depends on BaseMod). Its button rewrites `info.displayconfig` (lines: width, height, fps, fullscreen, borderless windowed, vsync) in the running app's working directory.
- `ObjC` is a minimal Objective-C runtime bridge: `msg*` helpers wrap `objc_msgSend`, and `dispatchMain` runs a callback on the Cocoa main thread. The only struct return is `msgPoint` (16-byte `NSPoint`, plain `objc_msgSend`; a struct over 16 bytes would need `objc_msgSend_stret` on x86_64).
- `BorderlessFullscreen` (`@SpireInitializer`) starts a daemon thread that schedules `tick()` on the main thread every `POLL_MS`. Each tick finds the LWJGL window (`MacOSXKeyableWindow`) and:
  - only if `info.displayconfig` said borderless windowed (fullscreen `false`, wfs `true`) at launch: sets its level to 26 while the app is active, 0 otherwise, and sets collection behavior `Managed | ParticipatesInCycle | FullScreenAuxiliary` so Mission Control lists it (neither is touched in fullscreen or normal windowed);
  - hides the cursor via `[NSCursor hide]` while the game window is the topmost window under the pointer.

## Design notes

- The approach mirrors Wine's macOS driver (`dlls/winemac.drv/cocoa_window.m` and `cocoa_app.m`): raise the window level instead of hiding the menu bar.
- Do not use `NSApplication setPresentationOptions:`. It hides the menu bar on all displays, and adding `DisableMenuBarTransitions` (bit 10) makes macOS silently ignore the call. An invalid combination throws an NSException through JNA and aborts the JVM.
- Windows above normal level vanish from Mission Control unless the collection behavior above is set; both are applied together.
- The game never grabs the mouse. It sets a 1x1 transparent native cursor once and draws its own. LWJGL only applies that cursor on mouse-entered events, which don't fire on launch or on re-activation, hence the native hide.
- `NSCursor hide`/`unhide` are counted, so they go through `hideCursor()`, which only calls on a state change. Any early exit from `tick()` must call `hideCursor(false)`.
- All AppKit calls must run on the main thread. Keep the `TICK` callback in a static field, or JNA garbage-collects it.
