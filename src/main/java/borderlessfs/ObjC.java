package borderlessfs;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Objective-C runtime bridge over JNA.
 *
 * objc_msgSend is called through libffi with a fixed (non-variadic) signature, which is what
 * arm64 requires. Only structs of 16 bytes or less (NSPoint) are returned, so no _stret variant
 * is needed on either arm64 or x86_64.
 */
final class ObjC {
    private ObjC() {}

    private static final NativeLibrary OBJC = NativeLibrary.getInstance("objc");
    private static final NativeLibrary SYSTEM = NativeLibrary.getInstance("System");

    private static final Function MSG = OBJC.getFunction("objc_msgSend");
    private static final Function GET_CLASS = OBJC.getFunction("objc_getClass");
    private static final Function SEL_REGISTER = OBJC.getFunction("sel_registerName");
    private static final Function CLASS_NAME = OBJC.getFunction("object_getClassName");

    private static final Function DISPATCH_ASYNC = SYSTEM.getFunction("dispatch_async_f");
    /** dispatch_get_main_queue() is a macro for &_dispatch_main_q. */
    private static final Pointer MAIN_QUEUE = SYSTEM.getGlobalVariableAddress("_dispatch_main_q");

    private static final Map<String, Pointer> SELECTORS = new HashMap<String, Pointer>();

    static synchronized Pointer sel(String name) {
        Pointer p = SELECTORS.get(name);
        if (p == null) {
            p = (Pointer) SEL_REGISTER.invoke(Pointer.class, new Object[]{name});
            SELECTORS.put(name, p);
        }
        return p;
    }

    static Pointer cls(String name) {
        return (Pointer) GET_CLASS.invoke(Pointer.class, new Object[]{name});
    }

    static String className(Pointer obj) {
        return CLASS_NAME.invokeString(new Object[]{obj}, false);
    }

    private static Object[] args(Pointer receiver, String selector, Object[] rest) {
        Object[] a = new Object[rest.length + 2];
        a[0] = receiver;
        a[1] = sel(selector);
        System.arraycopy(rest, 0, a, 2, rest.length);
        return a;
    }

    static Pointer msg(Pointer receiver, String selector, Object... rest) {
        return (Pointer) MSG.invoke(Pointer.class, args(receiver, selector, rest));
    }

    static void msgVoid(Pointer receiver, String selector, Object... rest) {
        MSG.invoke(Void.class, args(receiver, selector, rest));
    }

    static long msgLong(Pointer receiver, String selector, Object... rest) {
        return MSG.invokeLong(args(receiver, selector, rest));
    }

    /** BOOL is one byte on arm64 (bool) and x86_64 (signed char); upper register bits are undefined. */
    static boolean msgBool(Pointer receiver, String selector, Object... rest) {
        return (MSG.invokeLong(args(receiver, selector, rest)) & 0xFF) != 0;
    }

    /** A 16-byte struct is returned in registers on both arm64 and x86_64, so never _stret. */
    static NSPoint msgPoint(Pointer receiver, String selector) {
        return (NSPoint) MSG.invoke(NSPoint.ByValue.class, args(receiver, selector, new Object[0]));
    }

    interface DispatchFunction extends Callback {
        void invoke(Pointer context);
    }

    /** Runs fn on the Cocoa main thread. Keep a strong reference to fn so it isn't collected. */
    static void dispatchMain(DispatchFunction fn) {
        DISPATCH_ASYNC.invoke(Void.class, new Object[]{MAIN_QUEUE, Pointer.NULL, fn});
    }

    public static class NSPoint extends Structure {
        public double x, y;

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("x", "y");
        }

        public static class ByValue extends NSPoint implements Structure.ByValue {
            public ByValue() {}

            public ByValue(NSPoint p) {
                x = p.x;
                y = p.y;
            }
        }
    }
}
