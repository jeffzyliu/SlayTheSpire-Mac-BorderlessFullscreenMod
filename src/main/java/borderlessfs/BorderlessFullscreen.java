package borderlessfs;

import basemod.BaseMod;
import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static borderlessfs.ObjC.*;

/**
 * Borderless fullscreen for Slay the Spire on macOS.
 *
 * While the game is the active app, its window sits at a level above the menu bar and Dock (the
 * same approach as Wine's macOS driver). The mod never resizes or moves the window, so whatever
 * size the game picks is kept. Other displays are untouched. When the game loses focus the window drops back to normal level. The window is also
 * marked as managed so Mission Control lists it.
 *
 * All AppKit work happens on the Cocoa main thread; a daemon thread just schedules it.
 */
@SpireInitializer
public class BorderlessFullscreen {

    private static final long NORMAL_LEVEL = 0;            // NSNormalWindowLevel
    private static final long ABOVE_MENU_BAR_LEVEL = 26;   // NSStatusWindowLevel (25) + 1, as Wine does
    // Managed | ParticipatesInCycle | FullScreenAuxiliary, as Wine sets: keeps the window in Mission Control
    private static final long BEHAVIOR = (1 << 2) | (1 << 5) | (1 << 8);
    private static final long POLL_MS = 50;

    private static final AtomicBoolean pending = new AtomicBoolean(false);
    /** Display mode at launch; the window level is only raised in borderless windowed mode. */
    private static boolean borderless = false;
    private static boolean cursorHidden = false;
    private static boolean loggedError = false;

    /** Strong reference: JNA callbacks are collected if nothing on the Java side holds them. */
    private static final DispatchFunction TICK = new DispatchFunction() {
        @Override
        public void invoke(Pointer context) {
            try {
                tick();
            } catch (Throwable t) {
                if (!loggedError) {
                    loggedError = true;
                    System.err.println("[BorderlessFullscreen] " + t);
                    t.printStackTrace();
                }
            } finally {
                pending.set(false);
            }
        }
    };

    public static void initialize() {
        if (!Platform.isMac()) {
            System.out.println("[BorderlessFullscreen] Not macOS; doing nothing.");
            return;
        }
        BaseMod.subscribe(new SettingsPanel());
        borderless = launchedBorderlessWindowed();
        Thread poller = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (pending.compareAndSet(false, true)) {
                        dispatchMain(TICK);
                    }
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "BorderlessFullscreen");
        poller.setDaemon(true);
        poller.start();
        System.out.println("[BorderlessFullscreen] Started.");
    }

    /** info.displayconfig lines: width, height, fps, fullscreen, borderless windowed, vsync. */
    private static boolean launchedBorderlessWindowed() {
        try {
            List<String> lines = Files.readAllLines(Paths.get("info.displayconfig"), StandardCharsets.UTF_8);
            return lines.size() >= 5 && lines.get(3).trim().equals("false") && lines.get(4).trim().equals("true");
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs on the main thread. */
    private static void tick() {
        Pointer app = msg(cls("NSApplication"), "sharedApplication");
        Pointer window = findGameWindow(app);
        if (window == null) {
            hideCursor(false);
            return;
        }

        if (borderless) {
            long wantLevel = msgBool(app, "isActive") ? ABOVE_MENU_BAR_LEVEL : NORMAL_LEVEL;
            if (msgLong(window, "level") != wantLevel) {
                msgVoid(window, "setLevel:", wantLevel);
            }

            long behavior = msgLong(window, "collectionBehavior");
            if ((behavior & BEHAVIOR) != BEHAVIOR) {
                msgVoid(window, "setCollectionBehavior:", behavior | BEHAVIOR);
            }
        }

        // As in Wine's updateCursor: hide only while our window is the topmost window under the
        // pointer, so other displays and windows covering ours keep a cursor. The game always
        // wants its invisible cursor, so Wine's "client wants cursor hidden" flag is constant here.
        // LWJGL only applies that cursor on mouse-entered events, which don't fire on launch or
        // on re-activation over an already-hovered window.
        NSPoint mouse = msgPoint(cls("NSEvent"), "mouseLocation");
        long under = msgLong(cls("NSWindow"), "windowNumberAtPoint:belowWindowWithWindowNumber:",
                new NSPoint.ByValue(mouse), 0L);
        hideCursor(under == msgLong(window, "windowNumber"));
    }

    /** NSCursor hide/unhide calls are counted, so only call on a change to keep them balanced. */
    private static void hideCursor(boolean hide) {
        if (hide != cursorHidden) {
            msgVoid(cls("NSCursor"), hide ? "hide" : "unhide");
            cursorHidden = hide;
        }
    }

    /** LWJGL 2 creates an NSWindow subclass named MacOSXKeyableWindow; skip AWT/Swing leftovers. */
    private static Pointer findGameWindow(Pointer app) {
        Pointer windows = msg(app, "windows");
        long count = msgLong(windows, "count");
        Pointer fallback = null;
        for (long i = 0; i < count; i++) {
            Pointer w = msg(windows, "objectAtIndex:", i);
            if (w == null || !msgBool(w, "isVisible")) continue;
            String name = className(w);
            if (name.contains("Keyable")) return w;
            if (fallback == null && !name.startsWith("AWT")) fallback = w;
        }
        return fallback;
    }
}
