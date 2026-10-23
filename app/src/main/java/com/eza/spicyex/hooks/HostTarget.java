package com.eza.spicyex.hooks;

/**
 * Which host app this module process is currently serving. The Spotify-only
 * surface stack (native capture hooks, PiP controller, Android Auto prototype)
 * stays gated behind the Spotify package; the Samsung Music adapter flips
 * {@link #SAMSUNG} during module init and branches from there.
 */
public final class HostTarget {
    private HostTarget() {
    }

    public static volatile boolean SAMSUNG;
}
