package io.github.npfp222.hzunlock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

/**
 * Asks GitHub for HzUnlock's latest release. The request carries only a generic User-Agent with
 * the app's version; nothing about the phone, monitor, or user is sent. Call off the main thread.
 */
final class UpdateChecker {
    static final String RELEASES_URL = Report.REPO_URL + "/releases";
    private static final String LATEST_API = "https://api.github.com/repos/npfp222/HzUnlock/releases/latest";
    /** Automatic checks run at most this often. */
    static final long INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_BYTES = 256 * 1024;

    record Release(String version, String pageUrl, String notes) {}

    private UpdateChecker() {}

    /** The latest release when it's newer than {@code installed}, otherwise null. */
    static Release newerThan(String installed) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(LATEST_API).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "HzUnlock/" + installed);
        try {
            int code = connection.getResponseCode();
            if (code == 404) return null; // no published release yet
            if (code == 403 || code == 429) throw new IOException("GitHub is limiting requests. Try again later.");
            if (code != 200) throw new IOException("GitHub answered with code " + code + ".");
            String body;
            try (InputStream in = connection.getInputStream()) {
                body = new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
            }
            JSONObject release = new JSONObject(body);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
            String version = Versions.fromTag(release.optString("tag_name"));
            if (version.isEmpty() || Versions.compare(version, installed) <= 0) return null;
            String page = release.optString("html_url");
            // Only ever open this repository's own release page.
            if (!page.startsWith(RELEASES_URL + "/")) page = RELEASES_URL + "/latest";
            return new Release(version, page, Versions.plainNotes(release.optString("body"), 700));
        } finally {
            connection.disconnect();
        }
    }
}
