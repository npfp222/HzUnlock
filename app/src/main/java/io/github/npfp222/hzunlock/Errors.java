package io.github.npfp222.hzunlock;

import java.lang.reflect.InvocationTargetException;

/** Short, user-facing descriptions of failures. Safe to use from the app process. */
final class Errors {
    private Errors() {}

    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        if (message == null || message.isBlank()) return type;
        return type + ": " + message.substring(0, Math.min(message.length(), 180));
    }
}
