package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.util.Collections;
import java.util.WeakHashMap;

/**
 * Samsung Music entry surface. Samsung Music 16.x is a single-activity Compose
 * app (no Spotify-style fullscreen lyrics activity to take over), so this hook
 * adds a small toggle chip onto the player's window instead. Tapping it mounts
 * the very same native Spicy shell the Spotify takeover uses, fullscreen over
 * MusicMainActivity; re-tapping the chip or system back dismisses it.
 */
final class SamsungLyricsSurfaceHook {
    private static final String TAG = NativeSpicyLyricsHook.TAG;
    private static final String MAIN_ACTIVITY =
            "com.sec.android.app.music.common.activity.MusicMainActivity";
    private static final int TAG_ROOT = 0x53504C53; // SPLS - shared with the Spotify takeover
    private static final int TAG_CHIP = 0x53504C42; // SPLB
    private static final int TAG_COVER = 0x53504C43; // SPLC - shared with the Spotify takeover

    private static volatile boolean sessionActive;
    private static final Object BACK_LOCK = new Object();
    private static final java.util.Map<Activity, OnBackInvokedCallback> BACK_CALLBACKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final NativeSpicyLyricsHook host;

    SamsungLyricsSurfaceHook(NativeSpicyLyricsHook host) {
        this.host = host;
    }

    void hook() {
        // Samsung Music pulls its Compose surface in later than the first onCreate
        // callback returns, and re-invokes Window#setContentView on navigation, which
        // replaces the content root and wipes the chip along with it. Attach the chip
        // AFTER every setContent call: the window's context is the hosting activity.
        Runnable windowHookInstaller = () -> {
            // PhoneWindow is a hidden internal type, so it is not in the compile SDK;
            // resolve it reflectively (the app loader's parent chain includes the boot
            // classpath where the framework internals live).
            Class<?> windowClass = null;
            try {
                windowClass = Class.forName(
                        "com.android.internal.policy.PhoneWindow", false, getClass().getClassLoader());
            } catch (Throwable t) {
                XpLog.log(TAG + "samsung-surface PhoneWindow lookup failed: " + t);
            }
            if (windowClass == null) return;
            installContentHook(windowClass, "samsung:Window#setContentView.view",
                    new Class<?>[]{android.view.View.class});
            installContentHook(windowClass, "samsung:Window#setContentView.viewparams",
                    new Class<?>[]{android.view.View.class, android.view.ViewGroup.LayoutParams.class});
            installContentHook(windowClass, "samsung:Window#setContentView.res",
                    new Class<?>[]{int.class});
        };
        windowHookInstaller.run();

        // MusicMainActivity overrides onWindowFocusChanged without calling super on some
        // Samsung builds, so the parent-method hook never sees the app. onCreate is the one
        // lifecycle method every Activity subclass must call through, so chip placement
        // rides on it instead; the focus hook stays for the nothing-else-covers case.
        if (XpHooks.findAfter(Activity.class, "onCreate",
                "samsung:Activity#onCreate", p -> {
                    Activity activity = (Activity) p.thisObject;
                    if (!isMainWindow(activity)) return;
                    XpLog.log(TAG + "samsung-lifecycle onCreate main activity");
                    ensureChip(activity);
                    if (sessionActive) mount(activity);
                }, android.os.Bundle.class) != null) {
            XpLog.log(TAG + "samsung-surface onCreate hook installed");
        } else {
            XpLog.log(TAG + "samsung-surface onCreate hook NOT installed");
        }

        if (XpHooks.findAfter(Activity.class, "onWindowFocusChanged",
                "samsung:Activity#onWindowFocusChanged", p -> {
                    Activity activity = (Activity) p.thisObject;
                    if (p.args.length == 0 || !(Boolean) p.args[0]) return;
                    XpLog.log(TAG + "samsung-lifecycle focus " + activity.getClass().getName()
                            + (isMainWindow(activity) ? " main" : " other"));
                    if (!isMainWindow(activity)) return;
                    ensureChip(activity);
                    if (sessionActive) mount(activity);
                }, boolean.class) != null) {
            XpLog.log(TAG + "samsung-surface onWindowFocusChanged hook installed");
        } else {
            XpLog.log(TAG + "samsung-surface onWindowFocusChanged hook NOT installed");
        }

        if (XpHooks.findBefore(Activity.class, "onDestroy", "samsung:Activity#onDestroy", p -> {
            Activity activity = (Activity) p.thisObject;
            if (isMainWindow(activity)) detach(activity);
        }) != null) {
            XpLog.log("[SpicyEX] " + "samsung-surface onDestroy hook installed");
        } else {
            XpLog.log("[SpicyEX] " + "samsung-surface onDestroy hook NOT installed");
        }
    }

    private static boolean isMainWindow(Activity activity) {
        return MAIN_ACTIVITY.equals(activity.getClass().getName());
    }

    private void ensureChip(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG_CHIP);
            if (existing instanceof TextView) {
                existing.setVisibility(View.VISIBLE);
                return;
            }
            TextView chip = new TextView(activity);
            chip.setTag(TAG_CHIP);
            chip.setText("♪");
            chip.setTextSize(18);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setTextColor(Color.WHITE);
            chip.setPadding(dp(10), 0, dp(10), 0);
            chip.setGravity(Gravity.CENTER);
            chip.setContentDescription("Spicy lyrics");
            GradientDrawable background = new GradientDrawable();
            background.setColor(0xCCE52E66);
            background.setCornerRadius(dp(14));
            chip.setBackground(background);
            chip.setOnClickListener(v -> toggle(activity));
            XpLog.log("[SpicyEX] " + "samsung-surface chip added on " + activity.getClass().getSimpleName());
            FrameLayout.LayoutParams chipsParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
            chipsParams.gravity = Gravity.BOTTOM | Gravity.END;
            chipsParams.rightMargin = dp(16);
            chipsParams.bottomMargin = dp(88);
            chip.setLayoutParams(chipsParams);
            content.addView(chip);
        } catch (Throwable t) {
            XpLog.log(TAG + " chip install failed: " + t);
        }
    }

    private void installContentHook(Class<?> windowClass, String id, Class<?>[] paramTypes) {
        try {
            if (XpHooks.findAfter(windowClass, "setContentView", id, p -> {
                try {
                    android.view.Window window = (android.view.Window) p.thisObject;
                    if (window == null) return;
                    Activity activity = activityFromWindow(window);
                    if (activity == null || !isMainWindow(activity)) return;
                    ensureChip(activity);
                    if (sessionActive) mount(activity);
                } catch (Throwable t) {
                    XpLog.log(TAG + " setContentView hook failed: " + t);
                }
            }, paramTypes) != null) {
                XpLog.log(TAG + "samsung-surface " + id + " installed");
            } else {
                XpLog.log(TAG + "samsung-surface " + id + " NOT installed");
            }
        } catch (Throwable t) {
            XpLog.log(TAG + "samsung-surface " + id + " install threw: " + t);
        }
    }

    private static Activity activityFromWindow(android.view.Window window) {
        try {
            android.content.Context context = window.getContext();
            while (context instanceof android.content.ContextWrapper) {
                if (context instanceof Activity) return (Activity) context;
                context = ((android.content.ContextWrapper) context).getBaseContext();
            }
            return context instanceof Activity ? (Activity) context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void toggle(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            boolean hasRoot = content != null
                    && content.findViewWithTag(TAG_ROOT) instanceof NativeSpicyShellView;
            if (hasRoot) {
                sessionActive = false;
                removeRoot(activity);
            } else {
                mount(activity);
            }
        } catch (Throwable t) {
            XpLog.log(TAG + " toggle failed: " + t);
        }
    }

    private void mount(Activity activity) {
        try {
            if (activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            sessionActive = true;
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                XpLog.log(TAG + " Samsung content root missing");
                return;
            }
            View existing = content.findViewWithTag(TAG_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                ((NativeSpicyShellView) existing).start();
                hideCoveredSiblings(content, existing);
                ensureBack(activity);
                return;
            }
            NativeSpicyShellView root = new NativeSpicyShellView(host, activity);
            root.setTag(TAG_ROOT);
            root.setAlpha(0f);
            root.setTranslationY(dp(24));
            content.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            root.start();
            root.animate().alpha(1f).translationY(0f).setDuration(260)
                    .withEndAction(() -> hideCoveredSiblings(content, root)).start();
            ensureBack(activity);
            View chip = content.findViewWithTag(TAG_CHIP);
            if (chip instanceof TextView) chip.setVisibility(View.GONE);
            XpLog.log(TAG + " mounted native Spicy shell over Samsung Music player");
            Diagnostics.event("renderer", "mount_state",
                    Diagnostics.context("surface", "samsung_fullscreen", "mounted", "true"));
        } catch (Throwable t) {
            XpLog.log(TAG + " mount failed: " + t);
            Diagnostics.event("renderer", "mount_state", t,
                    Diagnostics.context("surface", "samsung_fullscreen", "mounted", "false"));
        }
    }

    private void removeRoot(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                NativeSpicyShellView shell = (NativeSpicyShellView) existing;
                restoreCoveredSiblings(shell);
                showChip(content);
                if (activity.isDestroyed() || activity.isFinishing()
                        || activity.isChangingConfigurations()) {
                    shell.stop();
                    content.removeView(shell);
                } else {
                    shell.animate().alpha(0f).translationY(dp(24)).setDuration(220)
                            .withEndAction(() -> {
                                try {
                                    shell.stop();
                                    content.removeView(shell);
                                } catch (Throwable t) {
                                    XpLog.log(TAG + " shell cleanup failed: " + t);
                                }
                            }).start();
                }
                XpLog.log(TAG + " removed native Spicy shell from Samsung Music player");
            } else {
                showChip(content);
            }
            unregisterBack(activity);
        } catch (Throwable t) {
            XpLog.log(TAG + " remove failed: " + t);
        }
    }

    /** Activity teardown path: strip the shell immediately, no animations. */
    private void detach(Activity activity) {
        try {
            unregisterBack(activity);
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                NativeSpicyShellView shell = (NativeSpicyShellView) existing;
                restoreCoveredSiblings(shell);
                shell.stop();
                content.removeView(shell);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void showChip(FrameLayout content) {
        try {
            View chip = content.findViewWithTag(TAG_CHIP);
            if (chip instanceof TextView) chip.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {
        }
    }

    private static void hideCoveredSiblings(FrameLayout content, View root) {
        try {
            if (content == null || root == null || root.getParent() != content) return;
            if (root.getTag(TAG_COVER) != null) return;
            java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
            int rootIndex = content.indexOfChild(root);
            for (int i = 0; i < rootIndex; i++) {
                View child = content.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                child.setVisibility(View.INVISIBLE);
                hidden.add(child);
            }
            root.setTag(TAG_COVER, hidden);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreCoveredSiblings(View root) {
        try {
            Object tag = root == null ? null : root.getTag(TAG_COVER);
            if (!(tag instanceof java.util.List)) return;
            root.setTag(TAG_COVER, null);
            for (Object o : (java.util.List<?>) tag) {
                if (o instanceof View && ((View) o).getVisibility() == View.INVISIBLE) {
                    ((View) o).setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureBack(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        synchronized (BACK_LOCK) {
            if (BACK_CALLBACKS.containsKey(activity)) return;
            OnBackInvokedCallback callback = () -> {
                synchronized (BACK_LOCK) {
                    if (!sessionActive) {
                        unregisterBack(activity);
                        return;
                    }
                }
                // Give the shell first say (layout editor sheets etc.), then dismiss.
                if (shellConsumesBack(activity)) return;
                sessionActive = false;
                removeRoot(activity);
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            BACK_CALLBACKS.put(activity, callback);
        }
    }

    private void unregisterBack(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        synchronized (BACK_LOCK) {
            OnBackInvokedCallback callback = BACK_CALLBACKS.remove(activity);
            if (callback != null) {
                try {
                    activity.getOnBackInvokedDispatcher()
                            .unregisterOnBackInvokedCallback(callback);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean shellConsumesBack(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            View root = content == null ? null : content.findViewWithTag(TAG_ROOT);
            return root instanceof NativeSpicyShellView && ((NativeSpicyShellView) root).consumeBack();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
