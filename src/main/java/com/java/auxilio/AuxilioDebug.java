package com.java.auxilio;

final class AuxilioDebug {
    private AuxilioDebug() {
    }

    static void trace(String owner, String method) {
        log(owner, "enter {}", method);
    }

    static void log(String owner, String message, Object... args) {
        if (!isDebugEnabled()) {
            return;
        }
        Object[] merged = new Object[args.length + 1];
        merged[0] = owner;
        System.arraycopy(args, 0, merged, 1, args.length);
        Auxilio.LOGGER.info("[{}] " + message, merged);
    }

    private static boolean isDebugEnabled() {
        try {
            return Config.DEBUG_MOUSE_TWEAKS.getAsBoolean();
        } catch (IllegalStateException ignored) {
            // Config is not loaded yet during early mod construction.
            return false;
        }
    }
}
