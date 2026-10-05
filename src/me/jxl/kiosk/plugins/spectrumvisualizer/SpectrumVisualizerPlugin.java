// SPDX-License-Identifier: MIT
package me.jxl.kiosk.plugins.spectrumvisualizer;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

public final class SpectrumVisualizerPlugin implements KioskPlugin {
    private PluginHost host;
    private Context context;
    private WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Application application;
    private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
    private Activity currentActivity;
    private BroadcastReceiver dreamReceiver;

    private boolean dreaming;
    private boolean kioskScreensaverActive;
    private String kioskScreensaverView = "";
    private boolean showOnKiosk = true;
    private boolean showOnFotoo = true;
    private boolean forcePreview;

    private String source = "Animated";
    private String position = "Bottom";
    private int widthPercent = 70;
    private int heightDp = 110;
    private int edgeOffsetDp = 24;
    private int opacity = 85;
    private int barCount = 32;
    private int fps = 20;
    private int gain = 3;
    private String colorMode = "Classic Winamp";
    private int singleColor = Color.WHITE;
    private int lowColor = Color.rgb(34, 197, 94);
    private int midColor = Color.rgb(250, 204, 21);
    private int highColor = Color.rgb(239, 68, 68);
    private int peakColor = Color.WHITE;
    private String mediaEntity = "";
    private boolean showOnlyWhenPlaying = true;
    private String mediaState = "";
    private String visibilityEntity = "";
    private String visibilityCondition = "Always";
    private String visibilityValue = "";
    private String visibilityState = "";
    private final Set<String> entitySubscriptions = new HashSet<>();

    private SpectrumView spectrumView;
    private volatile boolean analyzerRunning;
    private Thread analyzerThread;
    private AudioRecord audioRecord;
    private volatile Visualizer digitalVisualizer;
    private volatile int digitalAudioSessionId = -1;
    private volatile long digitalLastFrameAtMs = 0L;
    private volatile boolean digitalUsingSystemMix = false;
    private volatile int digitalLastPollResult = Integer.MIN_VALUE;
    private volatile int digitalSamplingRateMilliHz = 0;
    private byte[] digitalWaveform = new byte[0];
    private String digitalCapture = "Auto";
    private volatile AudioTrack discoveredTrack;
    private volatile long lastDiscoveryAtMs;
    private volatile String discoveryStatus = "not scanned";
    private volatile String digitalLastError = "";
    private volatile int digitalWaveformPeak = 0;
    private volatile double digitalWaveformRms = 0;
    // Enabled on startup for digital mode while diagnosing device capture.
    // Commands toggle it without consuming another manifest setting.
    private boolean debugEnabled = true;
    private boolean debugRequested;
    private TextView debugView;
    private final Runnable debugTick = new Runnable() {
        @Override public void run() {
            if (host == null || !debugEnabled) return;
            refreshDebugOverlay();
            updatePresentation();
            main.postDelayed(this, 1000L);
        }
    };

    @Override
    public synchronized void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.context = applicationContext(host);
        if (context == null) {
            host.status("Could not obtain Android application context.", true);
            return;
        }
        this.windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(context)) {
            host.status("Grant Display over other apps to Kiosk Satellite.", true);
            return;
        }

        registerDreamReceiver();
        registerActivityLifecycle();
        currentActivity = findResumedActivity();
        host.subscribe("screensaver.state");
        host.subscribe("screensaver.view");
        applySettings(settings);
        readInitialScreensaverState();
        host.status("Ready. Spectrum visualizer follows the selected screensavers.", false);
    }

    @Override
    public synchronized void configure(Map<String, Object> settings) {
        applySettings(settings);
    }

    @Override
    public synchronized void execute(String command, Map<String, Object> arguments) {
        if ("show".equals(command) || "test".equals(command)) {
            forcePreview = true;
            main.post(this::updatePresentation);
        } else if ("digitalStatus".equals(command)) {
            reportDigitalStatus();
        } else if ("showDebug".equals(command)) {
            debugEnabled = true;
            debugRequested = true;
            main.post(this::restartDebugOverlay);
        } else if ("hideDebug".equals(command)) {
            debugEnabled = false;
            main.post(() -> {
                main.removeCallbacks(debugTick);
                removeOverlayView(debugView);
                debugView = null;
            });
        } else if ("hide".equals(command)) {
            forcePreview = false;
            main.post(this::hideVisualizer);
        } else {
            throw new IllegalArgumentException("Unknown command: " + command);
        }
    }

    @Override
    public synchronized void onEvent(String event, Map<String, Object> payload) {
        if ("ks.screensaver.state".equals(event)) {
            kioskScreensaverActive = Boolean.TRUE.equals(payload.get("active"));
            if (!kioskScreensaverActive) kioskScreensaverView = "";
            main.post(this::updatePresentation);
            return;
        }
        if ("ks.screensaver.view".equals(event)) {
            Object value = payload.get("view");
            kioskScreensaverView =
                    value == null ? "" : String.valueOf(value);
            main.post(this::updatePresentation);
            return;
        }
        if (event.startsWith("ks.ha.entity.")) {
            Object id = payload.get("entityId");
            String entity = id == null
                    ? event.substring("ks.ha.entity.".length())
                    : String.valueOf(id);
            String state = payload.get("state") == null
                    ? "" : String.valueOf(payload.get("state"));
            boolean changed = false;
            if (!mediaEntity.isEmpty() && mediaEntity.equals(entity)) {
                mediaState = state;
                changed = true;
            }
            if (!visibilityEntity.isEmpty() && visibilityEntity.equals(entity)) {
                visibilityState = state;
                changed = true;
            }
            if (changed) main.post(this::updatePresentation);
        }
    }

    @Override
    public synchronized void stop() {
        for (String entity : new HashSet<>(entitySubscriptions)) {
            try { host.unsubscribe("ha.entity." + entity); } catch (Throwable ignored) {}
        }
        entitySubscriptions.clear();
        if (context != null && dreamReceiver != null) {
            try { context.unregisterReceiver(dreamReceiver); } catch (Throwable ignored) {}
        }
        if (application != null && lifecycleCallbacks != null) {
            try { application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks); } catch (Throwable ignored) {}
        }
        stopAnalyzer();
        main.removeCallbacks(debugTick);
        main.post(() -> {
            hideVisualizer();
            removeOverlayView(debugView);
            debugView = null;
        });
        currentActivity = null;
        host = null;
    }

    private void applySettings(Map<String, Object> values) {
        String target = stringSetting(values, "overlayTarget");
        showOnKiosk = !"Fotoo only".equals(target);
        showOnFotoo = !"Kiosk Satellite only".equals(target);

        String nextSource = stringSetting(values, "source");
        source = nextSource.isEmpty() ? "Animated" : nextSource;
        String nextPosition = stringSetting(values, "position");
        position = nextPosition.isEmpty() ? "Bottom" : nextPosition;
        widthPercent = intSetting(values, "widthPercent", 70, 30, 100);
        heightDp = intSetting(values, "heightDp", 110, 50, 300);
        edgeOffsetDp = intSetting(values, "edgeOffset", 24, 0, 300);
        opacity = intSetting(values, "opacity", 85, 20, 100);
        barCount = intSetting(values, "barCount", 32, 8, 64);
        fps = intSetting(values, "fps", 20, 5, 30);
        gain = intSetting(values, "gain", 3, 1, 10);
        String nextColorMode = stringSetting(values, "colorMode");
        colorMode = nextColorMode.isEmpty() ? "Classic Winamp" : nextColorMode;
        String nextDigitalCapture = stringSetting(values, "digitalCapture");
        digitalCapture = nextDigitalCapture.isEmpty() ? "Auto" : nextDigitalCapture;
        singleColor = colorSetting(values, "singleColor", Color.WHITE);
        lowColor = colorSetting(values, "lowColor", Color.rgb(34, 197, 94));
        midColor = colorSetting(values, "midColor", Color.rgb(250, 204, 21));
        highColor = colorSetting(values, "highColor", Color.rgb(239, 68, 68));
        peakColor = colorSetting(values, "peakColor", Color.WHITE);
        mediaEntity = stringSetting(values, "mediaEntity");
        showOnlyWhenPlaying =
                values.get("showOnlyWhenPlaying") == null ||
                Boolean.TRUE.equals(values.get("showOnlyWhenPlaying"));
        applyVisibilityRule(stringSetting(values, "visibilityRule"));

        Set<String> wanted = new HashSet<>();
        if (!mediaEntity.isEmpty()) wanted.add(mediaEntity);
        if (!visibilityEntity.isEmpty() &&
                !"Always".equals(visibilityCondition) &&
                !"Time between".equals(visibilityCondition)) {
            wanted.add(visibilityEntity);
        }
        for (String old : new HashSet<>(entitySubscriptions)) {
            if (!wanted.contains(old)) {
                try { host.unsubscribe("ha.entity." + old); } catch (Throwable ignored) {}
                entitySubscriptions.remove(old);
            }
        }
        mediaState = "";
        visibilityState = "";
        for (String entity : wanted) {
            if (entitySubscriptions.add(entity)) host.subscribe("ha.entity." + entity);
            pollEntity(entity);
        }

        main.post(() -> {
            stopAnalyzer();
            hideVisualizer();
            updatePresentation();
            restartDebugOverlay();
        });
    }

    private void applyVisibilityRule(String raw) {
        visibilityEntity = "";
        visibilityCondition = "Always";
        visibilityValue = "";

        if (raw == null || raw.trim().isEmpty()) return;

        String[] parts = raw.split("\\|", -1);
        if (parts.length < 2) {
            visibilityEntity = raw.trim();
            visibilityCondition = "Active";
            return;
        }

        String entity = parts[0].trim();
        String condition = canonicalVisibilityCondition(parts[1]);
        StringBuilder value = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) value.append('|');
            value.append(parts[i]);
        }

        visibilityCondition = condition;
        visibilityValue = value.toString().trim();

        if (!"Time between".equals(condition) &&
                !"time".equalsIgnoreCase(entity) &&
                !"@time".equalsIgnoreCase(entity)) {
            visibilityEntity = entity;
        }
    }

    private static String canonicalVisibilityCondition(String raw) {
        String value = raw == null
                ? ""
                : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if ("active".equals(value)) return "Active";
        if ("inactive".equals(value)) return "Inactive";
        if ("state equals".equals(value)) return "State equals";
        if ("state not equals".equals(value)) return "State not equals";
        if ("numeric above".equals(value)) return "Numeric above";
        if ("numeric below".equals(value)) return "Numeric below";
        if ("numeric between".equals(value)) return "Numeric between";
        if ("time between".equals(value)) return "Time between";
        return "Always";
    }

    private void pollEntity(String entity) {
        if (entity == null || entity.isEmpty() || host == null) return;
        Map<String, Object> args = new HashMap<>();
        args.put("entityId", entity);
        host.executeCommand("getHaEntityState", args, (ok, data, error) -> {
            if (!ok || !(data instanceof Map)) return;
            Object stateValue = ((Map<?, ?>) data).get("state");
            String state = stateValue == null ? "" : String.valueOf(stateValue);
            if (entity.equals(mediaEntity)) mediaState = state;
            if (entity.equals(visibilityEntity)) visibilityState = state;
            main.post(this::updatePresentation);
        });
    }

    private boolean overlayActive() {
        boolean kiosk = showOnKiosk && kioskScreensaverActive &&
                !"black".equals(kioskScreensaverView) &&
                !"blank".equals(kioskScreensaverView);
        if (!(forcePreview || kiosk || (showOnFotoo && dreaming))) return false;
        if (!forcePreview && !visibilityAllowed()) return false;
        if (!showOnlyWhenPlaying || mediaEntity.isEmpty()) return true;
        return "playing".equalsIgnoreCase(mediaState);
    }

    private boolean visibilityAllowed() {
        String condition = visibilityCondition == null ? "Always" : visibilityCondition;
        if (condition.isEmpty() || "Always".equals(condition)) return true;
        String value = visibilityValue == null ? "" : visibilityValue.trim();
        if ("Time between".equals(condition)) return timeBetween(value);
        if (visibilityEntity == null || visibilityEntity.isEmpty()) return true;

        String state = visibilityState == null ? "" : visibilityState.trim();
        if ("Active".equals(condition)) return activeState(state);
        if ("Inactive".equals(condition)) return !activeState(state);
        if ("State equals".equals(condition)) return state.equalsIgnoreCase(value);
        if ("State not equals".equals(condition)) return !state.equalsIgnoreCase(value);

        Double number = parseNumber(state);
        if (number == null) return false;
        if ("Numeric above".equals(condition)) {
            Double threshold = parseNumber(value);
            return threshold != null && number > threshold;
        }
        if ("Numeric below".equals(condition)) {
            Double threshold = parseNumber(value);
            return threshold != null && number < threshold;
        }
        if ("Numeric between".equals(condition)) {
            double[] bounds = parseRange(value);
            return bounds != null && number >= Math.min(bounds[0], bounds[1]) &&
                    number <= Math.max(bounds[0], bounds[1]);
        }
        return true;
    }

    private static boolean activeState(String state) {
        String s = state == null ? "" : state.trim().toLowerCase(java.util.Locale.ROOT);
        return "on".equals(s) || "true".equals(s) || "home".equals(s) ||
                "playing".equals(s) || "open".equals(s) || "detected".equals(s) ||
                "occupied".equals(s) || "present".equals(s);
    }

    private static Double parseNumber(String value) {
        try {
            return Double.parseDouble(value.trim().replace(',', '.'));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double[] parseRange(String value) {
        if (value == null) return null;
        String[] parts = value.trim().split("\\.\\.");
        if (parts.length != 2) return null;
        Double a = parseNumber(parts[0]);
        Double b = parseNumber(parts[1]);
        return a == null || b == null ? null : new double[] {a, b};
    }

    private static boolean timeBetween(String value) {
        if (value == null) return true;
        String[] parts = value.trim().split("\\s*-\\s*");
        if (parts.length != 2) return true;
        try {
            LocalTime from = LocalTime.parse(parts[0].trim());
            LocalTime until = LocalTime.parse(parts[1].trim());
            LocalTime now = LocalTime.now();
            if (from.equals(until)) return true;
            if (from.isBefore(until)) return !now.isBefore(from) && now.isBefore(until);
            return !now.isBefore(from) || now.isBefore(until);
        } catch (DateTimeParseException ignored) {
            return true;
        }
    }

    private void updatePresentation() {
        if (!overlayActive()) {
            hideVisualizer();
            return;
        }
        ensureVisualizer();
        startAnalyzer();
    }

    private void ensureVisualizer() {
        if (spectrumView != null || context == null) return;
        spectrumView = new SpectrumView(context);
        spectrumView.setTag("spectrum-visualizer-overlay:view");
        spectrumView.setAlpha(opacity / 100f);
        spectrumView.setColorConfig(
                colorMode, singleColor, lowColor, midColor, highColor, peakColor);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(220), screenWidth * widthPercent / 100);
        width = Math.min(screenWidth, width);
        int height = dp(heightDp);

        int gravity;
        if ("Top".equals(position)) gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        else if ("Center".equals(position)) gravity = Gravity.CENTER;
        else gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;

        try {
            if (!addOverlayView(
                    spectrumView,
                    width,
                    height,
                    gravity,
                    "Center".equals(position) ? 0 : dp(edgeOffsetDp))) {
                throw new IllegalStateException("No overlay host available");
            }
        } catch (Throwable error) {
            host.status("Visualizer overlay failed: " + safeMessage(error), true);
            spectrumView = null;
        }
    }

    private void hideVisualizer() {
        stopAnalyzer();
        View view = spectrumView;
        spectrumView = null;
        removeOverlayView(view);
    }

    private void startAnalyzer() {
        if (analyzerRunning || spectrumView == null) return;
        analyzerRunning = true;
        analyzerThread = new Thread(() -> {
            if ("Microphone".equals(source)) {
                if (!runMicrophoneAnalyzer()) runAnimatedAnalyzer();
            } else if ("Digital / Sendspin".equals(source)) {
                runDigitalAnalyzer();
            } else {
                runAnimatedAnalyzer();
            }
            analyzerRunning = false;
        }, "spectrum-visualizer");
        analyzerThread.setDaemon(true);
        analyzerThread.start();
    }

    private void stopAnalyzer() {
        analyzerRunning = false;
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
        }
        releaseDigitalVisualizer();
        Thread thread = analyzerThread;
        analyzerThread = null;
        if (thread != null) thread.interrupt();
    }

    private void runAnimatedAnalyzer() {
        double phase = 0;
        long sleep = Math.max(33, 1000L / Math.max(1, fps));
        while (analyzerRunning && spectrumView != null) {
            float[] bars = new float[barCount];
            for (int i = 0; i < bars.length; i++) {
                double wave =
                        0.48 +
                        0.24 * Math.sin(phase + i * 0.42) +
                        0.18 * Math.sin(phase * 0.61 + i * 0.17);
                bars[i] = clamp01((float) wave);
            }
            phase += 0.22;
            SpectrumView view = spectrumView;
            if (view != null) {
                view.setLevels(bars);
                view.postInvalidate();
            }
            sleep(sleep);
        }
    }

    /**
     * Digital mode attaches Android's Visualizer effect directly to the
     * AudioTrack owned by Kiosk Satellite's native Sendspin player. This is
     * the decoded digital stream, not microphone audio, so room noise and
     * speech do not affect the spectrum.
     *
     * Kiosk Satellite keeps the Sendspin bridge/session/output/track private;
     * all four live in the same app process, so the plugin resolves that
     * AudioTrack reflectively and attaches only to its audioSessionId.
     */
    private void runDigitalAnalyzer() {
        long noSignalSince = android.os.SystemClock.elapsedRealtime();
        int lastReportedSession = Integer.MIN_VALUE;
        long frameMs = Math.max(33L, 1000L / Math.max(1, fps));

        while (analyzerRunning && spectrumView != null) {
            AudioTrack track = resolveSendspinAudioTrack();
            int sessionId = -1;
            if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED) {
                sessionId = track.getAudioSessionId();
            }

            boolean forceMix =
                    "System output mix".equals(digitalCapture) ||
                    "Device playback".equals(digitalCapture);
            boolean forceSession = "Sendspin session".equals(digitalCapture);
            int wantedSession = forceMix ? 0 : sessionId;

            if (wantedSession <= 0 && !forceMix) {
                releaseDigitalVisualizer();
                zeroDigitalBars();
                if (host != null && lastReportedSession != -1) {
                    host.status(
                            "Digital visualizer: waiting for an active Sendspin AudioTrack.",
                            false);
                    lastReportedSession = -1;
                }
                sleep(250);
                continue;
            }

            if (digitalVisualizer == null || digitalAudioSessionId != wantedSession) {
                if (!attachDigitalVisualizer(wantedSession, wantedSession == 0)) {
                    if (!forceSession && wantedSession != 0) {
                        // Some Android builds only expose visualization data on
                        // the global output mix. This is still digital playback,
                        // never microphone input.
                        if (!attachDigitalVisualizer(0, true)) {
                            zeroDigitalBars();
                            sleep(500);
                            continue;
                        }
                    } else {
                        zeroDigitalBars();
                        sleep(500);
                        continue;
                    }
                }
                noSignalSince = android.os.SystemClock.elapsedRealtime();
                lastReportedSession = digitalAudioSessionId;
            }

            // Poll getWaveForm() directly. Android documents polling and
            // callback capture as two separate supported modes; some OEM
            // builds attach the effect successfully but never dispatch the
            // callback listener.
            boolean gotFrame = pollDigitalVisualizer();
            long now = android.os.SystemClock.elapsedRealtime();
            if (gotFrame) {
                noSignalSince = now;
            } else if ("Auto".equals(digitalCapture) &&
                    digitalAudioSessionId != 0 &&
                    now - noSignalSince > 1800L) {
                releaseDigitalVisualizer();
                if (!attachDigitalVisualizer(0, true)) {
                    zeroDigitalBars();
                }
                noSignalSince = now;
            } else if (now - noSignalSince > 2500L) {
                zeroDigitalBars();
            }

            sleep(frameMs);
        }
        releaseDigitalVisualizer();
    }

    private boolean attachDigitalVisualizer(int sessionId, boolean systemMix) {
        releaseDigitalVisualizer();
        try {
            final Visualizer vis = new Visualizer(sessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            int capture = 1024;
            if (range != null && range.length >= 2) {
                capture = Math.max(range[0], Math.min(range[1], 1024));
            }
            vis.setCaptureSize(capture);
            vis.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            vis.setEnabled(true);

            digitalVisualizer = vis;
            digitalAudioSessionId = sessionId;
            digitalUsingSystemMix = systemMix;
            digitalLastFrameAtMs = 0L;
            digitalLastPollResult = Integer.MIN_VALUE;
            digitalSamplingRateMilliHz = 0;
            digitalWaveform = new byte[capture];

            if (host != null) {
                host.status(
                        systemMix
                                ? "Digital spectrum attached to Android output mix (polling)."
                                : "Digital spectrum attached to Sendspin audio session " +
                                    sessionId + " (polling).",
                        false);
            }
            return true;
        } catch (Throwable error) {
            releaseDigitalVisualizer();
            digitalLastError = "attach session " + sessionId + ": " + safeMessage(error);
            if (host != null) {
                host.status(
                        "Digital spectrum attach failed for session " +
                                sessionId + ": " + safeMessage(error),
                        true);
            }
            return false;
        }
    }

    private boolean pollDigitalVisualizer() {
        Visualizer vis = digitalVisualizer;
        if (vis == null || !analyzerRunning) return false;
        try {
            int capture = vis.getCaptureSize();
            if (capture <= 0) return false;
            if (digitalWaveform == null || digitalWaveform.length < capture) {
                digitalWaveform = new byte[capture];
            }

            int result = vis.getWaveForm(digitalWaveform);
            digitalLastPollResult = result;
            if (result != Visualizer.SUCCESS) return false;

            int samplingRate = vis.getSamplingRate();
            digitalSamplingRateMilliHz = samplingRate;
            digitalLastFrameAtMs = android.os.SystemClock.elapsedRealtime();
            int peak = 0;
            double sumSquares = 0;
            for (byte value : digitalWaveform) {
                int sample = (value & 0xff) - 128;
                peak = Math.max(peak, Math.abs(sample));
                sumSquares += sample * sample;
            }
            digitalWaveformPeak = peak;
            digitalWaveformRms = Math.sqrt(sumSquares / digitalWaveform.length);
            digitalLastError = "";

            float[] bars = waveformSpectrumBars(
                    digitalWaveform, samplingRate, barCount, gain);
            SpectrumView view = spectrumView;
            if (view != null) {
                view.setLevels(bars);
                view.postInvalidate();
            }
            return true;
        } catch (Throwable error) {
            digitalLastPollResult = Integer.MIN_VALUE + 1;
            digitalLastError = "poll: " + safeMessage(error);
            return false;
        }
    }

    private void zeroDigitalBars() {
        SpectrumView view = spectrumView;
        if (view != null) {
            view.setLevels(new float[barCount]);
            view.postInvalidate();
        }
    }

    private String digitalStatusText() {
        AudioTrack track = resolveSendspinAudioTrack();
        String trackInfo;
        try {
        if (track == null) {
            trackInfo = "no Sendspin AudioTrack found";
        } else {
            trackInfo =
                    "trackState=" + track.getState() +
                    ", playState=" + track.getPlayState() +
                    ", session=" + track.getAudioSessionId() +
                    ", rate=" + track.getSampleRate();
        }
        } catch (Throwable error) {
            trackInfo = "track unavailable: " + safeMessage(error);
        }
        long age = digitalLastFrameAtMs <= 0
                ? -1
                : Math.max(
                        0L,
                        android.os.SystemClock.elapsedRealtime() -
                                digitalLastFrameAtMs);
        return "Spectrum 0.2.7 | " + source + " | " + digitalCapture +
                    "\n" + trackInfo +
                    "; attachedSession=" + digitalAudioSessionId +
                    "; systemMix=" + digitalUsingSystemMix +
                    "; pollResult=" + digitalLastPollResult +
                    "; sampleRateMilliHz=" + digitalSamplingRateMilliHz +
                    "; lastFrameAgeMs=" + age +
                    "\n" + discoveryStatus +
                    "\npeak=" + digitalWaveformPeak + "; rms=" +
                    String.format(java.util.Locale.ROOT, "%.1f", digitalWaveformRms) +
                    "; analyzer=" + analyzerRunning + "; barsView=" + (spectrumView != null) +
                    "\nmedia=" + mediaEntity + "; state=" + mediaState +
                    "; onlyPlaying=" + showOnlyWhenPlaying +
                    "\nscreensaver=" + kioskScreensaverActive + "/" + kioskScreensaverView +
                    "; dreaming=" + dreaming + "; visibility=" + visibilityAllowed() +
                    "; overlay=" + overlayActive() +
                    (digitalLastError.isEmpty() ? "" : "\nerror=" + digitalLastError);
    }

    private void reportDigitalStatus() {
        if (host == null) return;
        String status = digitalStatusText();
        // Diagnostic failures must never escape execute() and disable the plugin.
        try { host.status(status.substring(0, Math.min(1000, status.length())), false); }
        catch (Throwable error) { logDiagnosticFailure(error); }
        try {
            host.publishStatusTile("digital_source", "Spectrum digital source",
                    digitalLastFrameAtMs <= 0 ? "warn" : "on",
                    "session=" + digitalAudioSessionId + "; poll=" + digitalLastPollResult +
                    "; peak=" + digitalWaveformPeak + "; overlay=" + overlayActive());
        } catch (Throwable error) { logDiagnosticFailure(error); }
        debugEnabled = true;
        debugRequested = true;
        main.post(this::restartDebugOverlay);
    }

    private void logDiagnosticFailure(Throwable error) {
        try { if (host != null) host.log("Debug output: " + safeMessage(error)); }
        catch (Throwable ignored) {}
    }

    private void restartDebugOverlay() {
        main.removeCallbacks(debugTick);
        removeOverlayView(debugView);
        debugView = null;
        if (host != null && debugEnabled &&
                (debugRequested || "Digital / Sendspin".equals(source))) {
            debugTick.run();
        }
    }

    private void refreshDebugOverlay() {
        if (context == null || host == null) return;
        try {
            if (debugView == null) {
                TextView view = new TextView(context);
                view.setTextColor(Color.WHITE);
                view.setTextSize(12);
                view.setBackgroundColor(Color.argb(225, 0, 0, 0));
                view.setPadding(dp(10), dp(8), dp(10), dp(8));
                view.setText(digitalStatusText());
                int width = context.getResources().getDisplayMetrics().widthPixels * 95 / 100;
                if (!addOverlayView(view, width, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL, dp(12))) return;
                debugView = view;
            }
            debugView.setText(digitalStatusText());
            debugView.bringToFront();
        } catch (Throwable error) {
            removeOverlayView(debugView);
            debugView = null;
            logDiagnosticFailure(error);
        }
    }

    private static float[] waveformSpectrumBars(
            byte[] waveform,
            int samplingRateMilliHz,
            int bars,
            int gain) {
        int sourceN = waveform.length;
        int n = 1;
        while ((n << 1) <= sourceN && (n << 1) <= 2048) n <<= 1;
        if (n < 64) return new float[bars];

        double sampleRate = samplingRateMilliHz > 100000
                ? samplingRateMilliHz / 1000.0
                : samplingRateMilliHz;
        if (sampleRate <= 0) sampleRate = 48000.0;

        double[] real = new double[n];
        double[] imag = new double[n];
        for (int i = 0; i < n; i++) {
            // Android Visualizer waveform is unsigned PCM8 centered at 128.
            double sample = ((waveform[i] & 0xFF) - 128) / 128.0;
            double window =
                    0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (n - 1));
            real[i] = sample * window;
            imag[i] = 0.0;
        }
        fft(real, imag);
        return spectrumBars(real, imag, (int) Math.round(sampleRate), bars, gain);
    }

    private synchronized AudioTrack resolveSendspinAudioTrack() {
        try {
            if (discoveredTrack != null &&
                    discoveredTrack.getState() == AudioTrack.STATE_INITIALIZED) return discoveredTrack;
        } catch (Throwable ignored) {}
        discoveredTrack = null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastDiscoveryAtMs != 0 && now - lastDiscoveryAtMs < 1000L) return null;
        lastDiscoveryAtMs = now;
        Object app = context == null ? null : context.getApplicationContext();
        if (app == null) return null;
        String appPath = "app=" + app.getClass().getName();

        // Fast path for non-obfuscated/current Kiosk Satellite builds.
        try {
            Object bridge = fieldValue(app, "sendspin");
            Object session = fieldValue(bridge, "session");
            Object output = fieldValue(session, "output");
            Object track = fieldValue(output, "track");
            if (track instanceof AudioTrack) {
                AudioTrack audioTrack = (AudioTrack) track;
                if (audioTrack.getState() == AudioTrack.STATE_INITIALIZED) {
                    discoveredTrack = audioTrack;
                    discoveryStatus = appPath + "; foundAt=app.sendspin.session.output.track";
                    return audioTrack;
                }
            }
            appPath += "; appTrack=empty";
        } catch (Throwable error) {
            appPath += "; appPath=" + safeMessage(error);
        }

        // Release shrinking may remove the app's unused bridge-holder field.
        // EchoReference retains each live TrackTap independently. Read this
        // registry only: never call Source.take(), which consumes echo samples.
        try {
            Class<?> registry = Class.forName("me.jxl.kiosk_satellite.EchoReference",
                    false, context.getClassLoader());
            Object sources = AudioRegistrySnapshot.readSources(registry);
            int count = sources instanceof java.util.Collection
                    ? ((java.util.Collection<?>) sources).size() : -1;
            appPath += "; echoSources=" + count;
            if (sources instanceof Iterable) {
                for (Object source : (Iterable<?>) sources) {
                    AudioTrack hit = findLiveAudioTrack(source, 0, new java.util.IdentityHashMap<>());
                    if (hit != null) {
                        discoveredTrack = hit;
                        discoveryStatus = appPath + "; foundAt=echo.trackRegistry";
                        return hit;
                    }
                }
            }
        } catch (Throwable error) {
            appPath += "; echo=" + safeMessage(error);
        }

        // Compatibility path: walk only Kiosk Satellite objects and find a
        // live AudioTrack. This survives Kotlin/R8 private-field renaming and
        // small internal class changes.
        java.util.IdentityHashMap<Object, Boolean> seen =
                new java.util.IdentityHashMap<>();
        AudioTrack hit = findLiveAudioTrack(app, 0, seen);
        String location = "application graph";
        if (hit == null) {
            hit = findLiveAudioTrack(activeKioskActivity(), 0, new java.util.IdentityHashMap<>());
            location = "activity graph";
        }
        discoveredTrack = hit;
        discoveryStatus = appPath + "; foundAt=" + (hit == null ? "none" : location);
        return hit;
    }

    private static AudioTrack findLiveAudioTrack(
            Object value,
            int depth,
            java.util.IdentityHashMap<Object, Boolean> seen) {
        if (value == null || depth > 8 || seen.size() >= 256 ||
                seen.put(value, Boolean.TRUE) != null) {
            return null;
        }
        if (value instanceof AudioTrack) {
            AudioTrack track = (AudioTrack) value;
            try {
                if (track.getState() == AudioTrack.STATE_INITIALIZED &&
                        track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    return track;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        Class<?> type = value.getClass();
        String name = type.getName();
        if (!(name.startsWith("me.jxl.kiosk_satellite") ||
                name.startsWith("me.jxl.kiosk."))) {
            return null;
        }

        Class<?> current = type;
        while (current != null) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable error) {
                break;
            }
            for (Field field : fields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object child = field.get(value);
                    AudioTrack hit = findLiveAudioTrack(child, depth + 1, seen);
                    if (hit != null) return hit;
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        if (target == null) return null;
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private void releaseDigitalVisualizer() {
        Visualizer vis = digitalVisualizer;
        digitalVisualizer = null;
        digitalAudioSessionId = -1;
        digitalUsingSystemMix = false;
        digitalLastPollResult = Integer.MIN_VALUE;
        digitalSamplingRateMilliHz = 0;
        digitalWaveform = new byte[0];
        if (vis != null) {
            try { vis.setEnabled(false); } catch (Throwable ignored) {}
            try { vis.release(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Android Visualizer FFT format contains signed 8-bit real/imaginary
     * components. Convert its logarithmically-spaced frequency bins into the
     * same 0..1 / roughly -60..0 dB scale used by microphone mode.
     * samplingRate is reported by Android in milli-Hz.
     */
    private static float[] visualizerFftBars(
            byte[] fft,
            int samplingRateMilliHz,
            int bars,
            int gain) {
        int n = fft.length;
        float[] result = new float[bars];
        if (n < 8) return result;

        double sampleRate = samplingRateMilliHz > 100000
                ? samplingRateMilliHz / 1000.0
                : samplingRateMilliHz;
        if (sampleRate <= 0) sampleRate = 48000.0;

        double minHz = 45.0;
        double maxHz = Math.min(16000.0, sampleRate / 2.0);
        int maxBin = n / 2 - 1;

        for (int b = 0; b < bars; b++) {
            double lowHz = minHz * Math.pow(maxHz / minHz, b / (double) bars);
            double highHz =
                    minHz * Math.pow(maxHz / minHz, (b + 1) / (double) bars);
            int low = Math.max(1, (int) Math.floor(lowHz * n / sampleRate));
            int high = Math.min(
                    maxBin,
                    Math.max(low, (int) Math.ceil(highHz * n / sampleRate)));

            double peak = 0.0;
            for (int k = low; k <= high; k++) {
                int at = k * 2;
                if (at + 1 >= fft.length) break;
                double re = fft[at];
                double im = fft[at + 1];
                double magnitude = Math.hypot(re, im) / 181.0;
                if (magnitude > peak) peak = magnitude;
            }

            double db = 20.0 * Math.log10(Math.max(0.001, peak));
            double normalized = (db + 60.0) / 60.0;
            normalized *= (0.68 + gain * 0.16);
            result[b] = clamp01((float) normalized);
        }
        return result;
    }

    private boolean runMicrophoneAnalyzer() {
        if (Build.VERSION.SDK_INT >= 23 &&
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            host.status("Microphone mode needs the Kiosk Satellite microphone permission.", true);
            return false;
        }

        final int sampleRate = 44100;
        final int fftSize = 1024;
        int minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) minimum = fftSize * 4;
        int bufferBytes = Math.max(minimum, fftSize * 4);

        AudioRecord record;
        try {
            record = new AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                host.status("Microphone visualizer could not initialize AudioRecord; using animation.", true);
                return false;
            }
            audioRecord = record;
            record.startRecording();
        } catch (Throwable error) {
            host.status("Microphone visualizer unavailable: " + safeMessage(error), true);
            return false;
        }

        short[] pcm = new short[fftSize];
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];
        long frameMs = Math.max(33, 1000L / Math.max(1, fps));

        try {
            while (analyzerRunning && spectrumView != null) {
                int offset = 0;
                while (offset < fftSize && analyzerRunning) {
                    int read = record.read(pcm, offset, fftSize - offset);
                    if (read <= 0) break;
                    offset += read;
                }
                if (offset < fftSize) continue;

                for (int i = 0; i < fftSize; i++) {
                    double window =
                            0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (fftSize - 1));
                    real[i] = (pcm[i] / 32768.0) * window;
                    imag[i] = 0;
                }
                fft(real, imag);

                float[] bars = spectrumBars(real, imag, sampleRate, barCount, gain);
                SpectrumView view = spectrumView;
                if (view != null) {
                    view.setLevels(bars);
                    view.postInvalidate();
                }
                sleep(frameMs);
            }
            return true;
        } catch (Throwable error) {
            host.status("Microphone analyzer stopped: " + safeMessage(error), true);
            return false;
        } finally {
            if (audioRecord == record) audioRecord = null;
            try { record.stop(); } catch (Throwable ignored) {}
            try { record.release(); } catch (Throwable ignored) {}
        }
    }

    private static float[] spectrumBars(
            double[] real,
            double[] imag,
            int sampleRate,
            int bars,
            int gain) {
        int n = real.length;
        float[] result = new float[bars];
        double minHz = 45.0;
        double maxHz = Math.min(16000.0, sampleRate / 2.0);
        for (int b = 0; b < bars; b++) {
            double lowHz = minHz * Math.pow(maxHz / minHz, b / (double) bars);
            double highHz = minHz * Math.pow(maxHz / minHz, (b + 1) / (double) bars);
            int low = Math.max(1, (int) Math.floor(lowHz * n / sampleRate));
            int high = Math.min(n / 2 - 1, (int) Math.ceil(highHz * n / sampleRate));
            double peak = 0;
            for (int k = low; k <= high; k++) {
                double mag = Math.hypot(real[k], imag[k]) / (n / 2.0);
                if (mag > peak) peak = mag;
            }
            double db = 20.0 * Math.log10(Math.max(1e-8, peak));
            double normalized = (db + 62.0) / 62.0;
            normalized *= (0.65 + gain * 0.18);
            result[b] = clamp01((float) normalized);
        }
        return result;
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2.0 * Math.PI / len;
            double wLenR = Math.cos(angle);
            double wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wr = 1.0;
                double wi = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    double vr = real[v] * wr - imag[v] * wi;
                    double vi = real[v] * wi + imag[v] * wr;
                    real[v] = real[u] - vr;
                    imag[v] = imag[u] - vi;
                    real[u] += vr;
                    imag[u] += vi;
                    double nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }

    private boolean preferInAppOverlay() {
        return showOnKiosk && kioskScreensaverActive;
    }

    private boolean addOverlayView(
            View view, int width, int height, int gravity, int yOffset) {
        if (preferInAppOverlay()) {
            Activity activity = activeKioskActivity();
            if (activity != null) {
                View content = activity.findViewById(android.R.id.content);
                if (content instanceof FrameLayout) {
                    FrameLayout root = (FrameLayout) content;
                    FrameLayout.LayoutParams params =
                            new FrameLayout.LayoutParams(width, height, gravity);
                    if ((gravity & Gravity.TOP) == Gravity.TOP) params.topMargin = yOffset;
                    if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) params.bottomMargin = yOffset;
                    root.addView(view, params);
                    if (Build.VERSION.SDK_INT >= 21) view.setZ(100050f);
                    view.bringToFront();
                    return true;
                }
            }
        }

        if (windowManager == null) return false;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = gravity;
        params.y = yOffset;
        windowManager.addView(view, params);
        return true;
    }

    private void removeOverlayView(View view) {
        if (view == null) return;
        try {
            ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(view);
                return;
            }
        } catch (Throwable ignored) {}
        if (windowManager != null) {
            try { windowManager.removeViewImmediate(view); } catch (Throwable ignored) {}
        }
    }

    private void readInitialScreensaverState() {
        host.executeCommand(
                "isScreensaverActive",
                Collections.emptyMap(),
                (ok, data, error) -> {
                    if (ok && data instanceof Boolean) {
                        kioskScreensaverActive = (Boolean) data;
                        main.post(this::updatePresentation);
                    }
                });
    }

    private void registerDreamReceiver() {
        dreamReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_DREAMING_STARTED.equals(intent.getAction())) {
                    dreaming = true;
                    updatePresentation();
                } else if (Intent.ACTION_DREAMING_STOPPED.equals(intent.getAction())) {
                    dreaming = false;
                    forcePreview = false;
                    updatePresentation();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(dreamReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(dreamReceiver, filter);
        }
    }

    private void registerActivityLifecycle() {
        Context appContext = context.getApplicationContext();
        if (!(appContext instanceof Application)) return;
        application = (Application) appContext;
        lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {
                if (a.getPackageName().equals(context.getPackageName())) currentActivity = a;
            }
            @Override public void onActivityPaused(Activity a) {
                if (currentActivity == a) currentActivity = null;
            }
            @Override public void onActivityStopped(Activity a) {
                if (currentActivity == a) currentActivity = null;
            }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {
                if (currentActivity == a) currentActivity = null;
            }
        };
        application.registerActivityLifecycleCallbacks(lifecycleCallbacks);
    }

    private Activity activeKioskActivity() {
        Activity a = currentActivity;
        if (a != null && !a.isFinishing() &&
                (Build.VERSION.SDK_INT < 17 || !a.isDestroyed())) return a;
        a = findResumedActivity();
        if (a != null) currentActivity = a;
        return a;
    }

    private Activity findResumedActivity() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Method currentThread =
                    threadClass.getDeclaredMethod("currentActivityThread");
            currentThread.setAccessible(true);
            Object thread = currentThread.invoke(null);
            if (thread == null) return null;
            Field activitiesField = threadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activitiesObject = activitiesField.get(thread);
            if (!(activitiesObject instanceof Map)) return null;
            Activity fallback = null;
            for (Object record : ((Map<?, ?>) activitiesObject).values()) {
                if (record == null) continue;
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object value = activityField.get(record);
                if (!(value instanceof Activity)) continue;
                Activity a = (Activity) value;
                if (!a.getPackageName().equals(context.getPackageName()) ||
                        a.isFinishing() ||
                        (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) continue;
                if (a.hasWindowFocus()) return a;
                fallback = a;
            }
            return fallback;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context applicationContext(PluginHost host) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication =
                    activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object value = currentApplication.invoke(null);
            if (value instanceof Application) {
                return ((Application) value).getApplicationContext();
            }
            if (value instanceof Context) {
                return ((Context) value).getApplicationContext();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String stringSetting(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int intSetting(
            Map<String, Object> values,
            String key,
            int fallback,
            int min,
            int max) {
        Object value = values == null ? null : values.get(key);
        int result = value instanceof Number
                ? ((Number) value).intValue()
                : fallback;
        return Math.max(min, Math.min(max, result));
    }

    private static int colorSetting(
            Map<String, Object> values,
            String key,
            int fallback) {
        String raw = stringSetting(values, key);
        if (raw.isEmpty()) return fallback;
        try {
            return Color.parseColor(raw);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static String safeMessage(Throwable error) {
        String value = error == null ? null : error.getMessage();
        return value == null || value.isEmpty()
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : value;
    }

    private static final class SpectrumView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint peakPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private volatile float[] levels = new float[0];
        private float[] smoothed = new float[0];
        private float[] peaks = new float[0];

        private String colorMode = "Classic Winamp";
        private int singleColor = Color.WHITE;
        private int lowColor = Color.rgb(34, 197, 94);
        private int midColor = Color.rgb(250, 204, 21);
        private int highColor = Color.rgb(239, 68, 68);
        private int peakColor = Color.WHITE;

        SpectrumView(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
        }

        void setColorConfig(
                String mode,
                int single,
                int low,
                int mid,
                int high,
                int peak) {
            colorMode = mode == null || mode.isEmpty() ? "Classic Winamp" : mode;
            singleColor = single;
            lowColor = low;
            midColor = mid;
            highColor = high;
            peakColor = peak;
            invalidate();
        }

        void setLevels(float[] values) {
            levels = values == null ? new float[0] : values.clone();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float[] incoming = levels;
            if (incoming.length == 0) return;
            if (smoothed.length != incoming.length) {
                smoothed = new float[incoming.length];
                peaks = new float[incoming.length];
            }

            float gap = Math.max(2f, getWidth() * 0.0035f);
            float barWidth =
                    (getWidth() - gap * (incoming.length - 1)) / incoming.length;
            float usable = getHeight() - 8f;

            Shader classicShader = null;
            if ("Classic Winamp".equals(colorMode)) {
                // The gradient is tied to level height: short/quiet bars stay
                // green, medium bars reach yellow, and loud bars reach red.
                classicShader = new LinearGradient(
                        0f,
                        getHeight(),
                        0f,
                        0f,
                        new int[] {lowColor, midColor, highColor},
                        new float[] {0f, 0.58f, 1f},
                        Shader.TileMode.CLAMP);
            }

            peakPaint.setShader(null);
            peakPaint.setColor(peakColor);

            for (int i = 0; i < incoming.length; i++) {
                float target = clamp01(incoming[i]);
                smoothed[i] = target > smoothed[i]
                        ? smoothed[i] * 0.35f + target * 0.65f
                        : smoothed[i] * 0.82f + target * 0.18f;
                peaks[i] = Math.max(smoothed[i], peaks[i] - 0.025f);

                paint.setShader(null);
                if ("Classic Winamp".equals(colorMode)) {
                    paint.setShader(classicShader);
                } else if ("Rainbow".equals(colorMode)) {
                    float hue = incoming.length <= 1
                            ? 0f
                            : (i * 300f / (incoming.length - 1));
                    paint.setColor(Color.HSVToColor(new float[] {hue, 0.88f, 1f}));
                } else if ("Level heat".equals(colorMode)) {
                    paint.setColor(levelColor(
                            smoothed[i], lowColor, midColor, highColor));
                } else {
                    paint.setColor(singleColor);
                }

                float left = i * (barWidth + gap);
                float top = getHeight() - smoothed[i] * usable;
                canvas.drawRoundRect(
                        left, top, left + barWidth, getHeight(),
                        Math.min(8f, barWidth / 2f),
                        Math.min(8f, barWidth / 2f),
                        paint);

                float peakY = getHeight() - peaks[i] * usable;
                canvas.drawRect(
                        left,
                        Math.max(0, peakY - 2f),
                        left + barWidth,
                        peakY,
                        peakPaint);
            }

            paint.setShader(null);
        }

        private static int levelColor(float level, int low, int mid, int high) {
            float v = clamp01(level);
            if (v <= 0.58f) {
                return blend(low, mid, v / 0.58f);
            }
            return blend(mid, high, (v - 0.58f) / 0.42f);
        }

        private static int blend(int from, int to, float amount) {
            float t = clamp01(amount);
            int a = Math.round(Color.alpha(from) +
                    (Color.alpha(to) - Color.alpha(from)) * t);
            int r = Math.round(Color.red(from) +
                    (Color.red(to) - Color.red(from)) * t);
            int g = Math.round(Color.green(from) +
                    (Color.green(to) - Color.green(from)) * t);
            int b = Math.round(Color.blue(from) +
                    (Color.blue(to) - Color.blue(from)) * t);
            return Color.argb(a, r, g, b);
        }
    }}
