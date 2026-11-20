package io.github.npfp222.hzunlock;

import android.content.Context;
import android.content.res.Configuration;

/** iOS-style system colors for the current theme, shared by every screen. */
final class Palette {
    final boolean dark;
    final int bg, card, label, secondary, separator, fill, accent, warn, link;

    Palette(boolean dark) {
        this.dark = dark;
        bg = dark ? 0xFF000000 : 0xFFF2F2F7;
        card = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        label = dark ? 0xFFFFFFFF : 0xFF000000;
        secondary = dark ? 0xFF8D8D93 : 0xFF8A8A8E;
        separator = dark ? 0xFF38383A : 0xFFC6C6C8;
        fill = dark ? 0xFF39393D : 0xFFE9E9EB;
        accent = dark ? 0xFF30D158 : 0xFF34C759;
        warn = dark ? 0xFFFF9F0A : 0xFFFF9500;
        link = dark ? 0xFF0A84FF : 0xFF007AFF;
    }

    static Palette of(Context context) {
        return new Palette((context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES);
    }
}
