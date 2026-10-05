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
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
    private String mediaEntity = "";
    private boolean showOnlyWhenPlaying = true;
    private String mediaState = "";

    private SpectrumView spectrumView;
    private volatile boolean analyzerRunning;
    private Thread analyzerThread;
    private AudioRecord audioRecord;

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
        if (event.startsWith("ks.ha.entity.") && !mediaEntity.isEmpty()) {
            Object id = payload.get("entityId");
            String entity = id == null
                    ? event.substring("ks.ha.entity.".length())
                    : String.valueOf(id);
            if (mediaEntity.equals(entity)) {
                mediaState = payload.get("state") == null
                        ? "" : String.valueOf(payload.get("state"));
                main.post(this::updatePresentation);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!mediaEntity.isEmpty()) {
            try { host.unsubscribe("ha.entity." + mediaEntity); } catch (Throwable ignored) {}
        }
        if (context != null && dreamReceiver != null) {
            try { context.unregisterReceiver(dreamReceiver); } catch (Throwable ignored) {}
        }
        if (application != null && lifecycleCallbacks != null) {
            try { application.unregisterActivityLifecycleCallbacks(lifecycleCallbacks); } catch (Throwable ignored) {}
        }
        stopAnalyzer();
        main.post(this::hideVisualizer);
        currentActivity = null;
        host = null;
    }

    private void applySettings(Map<String, Object> values) {
        String oldMedia = mediaEntity;

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
        mediaEntity = stringSetting(values, "mediaEntity");
        showOnlyWhenPlaying =
                values.get("showOnlyWhenPlaying") == null ||
                Boolean.TRUE.equals(values.get("showOnlyWhenPlaying"));

        if (!oldMedia.equals(mediaEntity)) {
            if (!oldMedia.isEmpty()) {
                try { host.unsubscribe("ha.entity." + oldMedia); } catch (Throwable ignored) {}
            }
            mediaState = "";
            if (!mediaEntity.isEmpty()) {
                host.subscribe("ha.entity." + mediaEntity);
                pollMedia();
            }
        }

        main.post(() -> {
            stopAnalyzer();
            hideVisualizer();
            updatePresentation();
        });
    }

    private void pollMedia() {
        if (mediaEntity.isEmpty() || host == null) return;
        Map<String, Object> args = new HashMap<>();
        args.put("entity_id", mediaEntity);
        host.executeCommand("getHaEntityState", args, (ok, data, error) -> {
            if (!ok || !(data instanceof Map)) return;
            Object state = ((Map<?, ?>) data).get("state");
            mediaState = state == null ? "" : String.valueOf(state);
            main.post(this::updatePresentation);
        });
    }

    private boolean overlayActive() {
        boolean kiosk = showOnKiosk && kioskScreensaverActive &&
                !"black".equals(kioskScreensaverView) &&
                !"blank".equals(kioskScreensaverView);
        if (!(forcePreview || kiosk || (showOnFotoo && dreaming))) return false;
        if (!showOnlyWhenPlaying || mediaEntity.isEmpty()) return true;
        return "playing".equalsIgnoreCase(mediaState);
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

        SpectrumView(Context context) {
            super(context);
            paint.setColor(Color.WHITE);
            peakPaint.setColor(0xFFE5E5E5);
            setBackgroundColor(Color.TRANSPARENT);
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

            for (int i = 0; i < incoming.length; i++) {
                float target = clamp01(incoming[i]);
                smoothed[i] = target > smoothed[i]
                        ? smoothed[i] * 0.35f + target * 0.65f
                        : smoothed[i] * 0.82f + target * 0.18f;
                peaks[i] = Math.max(smoothed[i], peaks[i] - 0.025f);

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
        }
    }
}
