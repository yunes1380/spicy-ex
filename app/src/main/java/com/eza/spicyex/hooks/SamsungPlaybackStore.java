package com.eza.spicyex.hooks;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;

import com.eza.spicyex.SpotifyTrack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Samsung Music adapter state. The module only needs two inputs from the host:
 * the current track's identity and how far playback has come. Samsung Music
 * publishes both through its own framework MediaSession, so the shared
 * {@code MediaSession#setMetadata / setPlaybackState} hooks in PlaybackBridge
 * feed this store instead of Spotify's PlayerState reflection path.
 *
 * <p>Track URIs are fabricated deterministically (same song -> same id across
 * sessions) in the {@code scheme:track:ID} shape LyricUtils.trackIdFromUri
 * expects, so the lyric repository's catalog/cache/capture identity logic
 * keeps working without Spotify. Remote lyric sources that need a Spotify id
 * simply skip; LRCLIB and AMLL match on title/artist alone.
 */
public final class SamsungPlaybackStore {
    private static final Object LOCK = new Object();
    private static String sTitle;
    private static String sArtist = "";
    private static String sAlbum = "";
    private static long sDurationMs;
    private static long sBasePosMs = -1;
    private static long sWallMs;
    private static double sSpeed = 1d;
    private static boolean sPlaying;

    private SamsungPlaybackStore() {
    }

    public static void onMetadata(MediaMetadata md) {
        if (md == null) return;
        try {
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null || title.trim().isEmpty()) return;
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null || artist.trim().isEmpty()) {
                artist = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            String album = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
            long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            synchronized (LOCK) {
                sTitle = title.trim();
                sArtist = artist == null ? "" : artist.trim();
                sAlbum = album == null ? "" : album.trim();
                if (duration > 0) sDurationMs = duration;
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onPlaybackState(PlaybackState state) {
        if (state == null) return;
        try {
            int playState = state.getState();
            double speed = state.getPlaybackSpeed();
            long pos = state.getPosition();
            synchronized (LOCK) {
                sPlaying = playState == PlaybackState.STATE_PLAYING
                        || playState == PlaybackState.STATE_BUFFERING;
                if (speed > 0d && speed < 8d) sSpeed = speed;
                if (pos >= 0) {
                    sBasePosMs = pos;
                    sWallMs = System.currentTimeMillis();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean hasTrack() {
        synchronized (LOCK) {
            return sTitle != null;
        }
    }

    /** Latest track snapshot, with the playback position advanced to "now". */
    public static SpotifyTrack toTrack() {
        String title;
        String artist;
        String album;
        long duration;
        long base;
        long wall;
        double speed;
        boolean playing;
        synchronized (LOCK) {
            title = sTitle;
            if (title == null) return null;
            artist = sArtist;
            album = sAlbum;
            duration = sDurationMs;
            base = sBasePosMs;
            wall = sWallMs;
            speed = sSpeed;
            playing = sPlaying;
        }
        long position = base;
        if (position >= 0 && playing && wall > 0) {
            long delta = System.currentTimeMillis() - wall;
            if (delta > 0) position += Math.round(delta * speed);
        }
        if (duration > 0 && position > duration) position = duration;
        if (position < 0) position = 0;
        return new SpotifyTrack(title, artist, album, uri(title, artist, duration),
                position, null, System.currentTimeMillis(), null, duration, false);
    }

    static String uri(String title, String artist, long duration) {
        StringBuilder seed = new StringBuilder();
        append(seed, artist);
        seed.append((char) 1);
        append(seed, title);
        seed.append((char) 1).append(duration);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("samsmusic:track:");
            for (int i = 0; i < 20; i++) {
                out.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
                out.append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return out.toString();
        } catch (Throwable ignored) {
            return "samsmusic:track:unknown";
        }
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null) builder.append(value.replace((char) 1, ' '));
    }
}
