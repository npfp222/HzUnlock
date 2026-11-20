package io.github.npfp222.hzunlock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/**
 * Asks GitHub for HzUnlock's recent releases. The request carries only a generic User-Agent with
 * the app's version; nothing about the phone, monitor, or user is sent. Call off the main thread.
 *
 * <p>Small updates are published as ordinary releases. A release whose notes contain
 * {@code <!-- notify -->} is a checkpoint: only checkpoints trigger the automatic popup, while
 * Check for Updates reports any newer release.
 */
final class UpdateChecker {
    static final String RELEASES_URL = Report.REPO_URL + "/releases";
    private static final String RELEASES_API = "https://api.github.com/repos/npfp222/HzUnlock/releases?per_page=30";
    /** Automatic checks run at most this often. */
    static final long INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 1024 * 1024;

    /**
     * A newer release. {@code version} and {@code pageUrl} are the newest release, which is what
     * to download. {@code checkpoint} is the newest checkpoint release newer than the installed
     * version, or null when there are only small updates. {@code notes} come from the checkpoint
     * when there is one, since those are the notes worth reading.
     */
    record Update(String version, String pageUrl, String notes, String checkpoint) {}

    private UpdateChecker() {}

    /** What's newer than {@code installed}, or null when it's current. */
    static Update newerThan(String installed) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(RELEASES_API).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "HzUnlock/" + installed);
        String body;
        try {
            int code = connection.getResponseCode();
            if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests. Try again later.");
            if (code != 200) throw new IOException("GitHub answered with code " + code + ".");
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
        JSONArray list = new JSONArray(body);
        List<Versions.Candidate> releases = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject release = list.optJSONObject(i);
            if (release == null) continue;
            String page = release.optString("html_url");
            // Only ever open this repository's own release pages.
            if (!page.startsWith(RELEASES_URL + "/")) page = RELEASES_URL + "/latest";
            releases.add(new Versions.Candidate(Versions.fromTag(release.optString("tag_name")), page,
                    release.optString("body"), release.optBoolean("draft"), release.optBoolean("prerelease")));
        }
        Versions.Choice choice = Versions.choose(releases, installed);
        if (choice.newest() == null) return null;
        Versions.Candidate notesFrom = choice.checkpoint() != null ? choice.checkpoint() : choice.newest();
        return new Update(choice.newest().version(), choice.newest().pageUrl(),
                Versions.plainNotes(notesFrom.notes(), 700),
                choice.checkpoint() == null ? null : choice.checkpoint().version());
    }
}
