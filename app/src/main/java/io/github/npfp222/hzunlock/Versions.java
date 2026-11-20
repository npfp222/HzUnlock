package io.github.npfp222.hzunlock;

import java.util.regex.Pattern;

/** Version comparison and release-note cleanup for the update check. Plain Java for testing. */
final class Versions {
    private Versions() {}

    /**
     * Compares dotted versions numerically ("1.0.10" is newer than "1.0.9"). A leading "v" and
     * anything after "-" or "+" are ignored. Returns a negative number, zero, or a positive number.
     */
    static int compare(String a, String b) {
        int[] left = parts(a);
        int[] right = parts(b);
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int x = i < left.length ? left[i] : 0;
            int y = i < right.length ? right[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int[] parts(String version) {
        String text = version == null ? "" : version.trim();
        if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1);
        int cut = text.length();
        for (char stop : new char[]{'-', '+', ' '}) {
            int at = text.indexOf(stop);
            if (at >= 0) cut = Math.min(cut, at);
        }
        String[] pieces = text.substring(0, cut).split("\\.");
        int[] numbers = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String digits = pieces[i].replaceAll("\\D.*$", "");
            try {
                numbers[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    /**
     * Release notes containing this HTML comment mark a checkpoint release: the automatic update
     * check announces only those. GitHub doesn't show HTML comments on the release page.
     */
    private static final Pattern NOTIFY =
            Pattern.compile("<!--\\s*notify\\s*-->", Pattern.CASE_INSENSITIVE);

    static boolean isCheckpoint(String notes) {
        return notes != null && NOTIFY.matcher(notes).find();
    }

    /** One published release, as listed by GitHub. */
    record Candidate(String version, String pageUrl, String notes, boolean draft, boolean prerelease) {}

    /**
     * What's newer than {@code installed}: the newest release of all (what to download) and the
     * newest checkpoint release (what decides whether to announce it). Either may be null.
     */
    record Choice(Candidate newest, Candidate checkpoint) {}

    static Choice choose(Iterable<Candidate> releases, String installed) {
        Candidate newest = null;
        Candidate checkpoint = null;
        for (Candidate release : releases) {
            if (release.draft() || release.prerelease() || release.version().isEmpty()) continue;
            if (compare(release.version(), installed) <= 0) continue;
            if (newest == null || compare(release.version(), newest.version()) > 0) newest = release;
            if (isCheckpoint(release.notes())
                    && (checkpoint == null || compare(release.version(), checkpoint.version()) > 0)) {
                checkpoint = release;
            }
        }
        return new Choice(newest, checkpoint);
    }

    /** "v1.0.2" → "1.0.2". */
    static String fromTag(String tag) {
        String text = tag == null ? "" : tag.trim();
        return text.startsWith("v") || text.startsWith("V") ? text.substring(1) : text;
    }

    /**
     * Release notes as plain text for a dialog: headings, bold, code marks and links are reduced
     * to their text, the "Verify the download" section is dropped, and the result is cut at a
     * line boundary to about {@code maxChars}.
     */
    static String plainNotes(String markdown, int maxChars) {
        if (markdown == null) return "";
        StringBuilder out = new StringBuilder();
        boolean truncated = false;
        String cleaned = markdown.replace("\r", "").replaceAll("(?s)<!--.*?-->", "");
        for (String raw : cleaned.split("\n")) {
            String line = raw.stripTrailing();
            if (line.startsWith("#") && line.toLowerCase(java.util.Locale.ROOT).contains("verify")) break;
            if (line.startsWith("# ")) continue; // the release title repeats the dialog's title
            line = line.replaceAll("^#+\\s*", "")
                    .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                    .replace("**", "")
                    .replace("`", "")
                    .replaceAll("^(\\s*)[-*] ", "$1• ");
            if (line.isBlank() && (out.length() == 0 || out.toString().endsWith("\n\n"))) continue;
            if (out.length() + line.length() + 1 > maxChars) {
                truncated = true;
                break;
            }
            out.append(line).append('\n');
        }
        String text = out.toString().strip();
        return truncated ? text + "\n…" : text;
    }
}
