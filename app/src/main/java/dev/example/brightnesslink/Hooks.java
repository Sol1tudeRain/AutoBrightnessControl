package dev.example.brightnesslink;

import android.content.Context;
import android.provider.Settings;
import android.hardware.display.DisplayManager;
import android.view.Display;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * AOSP-oriented Xposed hooks; ROM specific classes/methods are intentionally optional.
 * Settings are read in the privileged target processes; the controller WebUI writes them.
 * Defaults OFF so installation itself cannot change display behavior.
 */
public final class Hooks implements IXposedHookLoadPackage {
    private static final String AUTO_ENABLED = "brightnesslink_auto_enabled";
    private static final String AUTO_CAP = "brightnesslink_auto_cap";
    private static final String AOD_ENABLED = "brightnesslink_aod_enabled";
    private static final String AOD_RATIO = "brightnesslink_aod_ratio";
    private static final String TAG = "BrightnessLink: ";

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // LSPosed commonly exposes system_server as package/process "android".
        if ("android".equals(lpparam.packageName)
                && ("android".equals(lpparam.processName)
                    || "system_server".equals(lpparam.processName))) {
            hookAutomaticBrightness(lpparam.classLoader);
            hookDreamManagerDoze(lpparam.classLoader);
        }
        if ("com.android.systemui".equals(lpparam.packageName)
                && "com.android.systemui".equals(lpparam.processName)) {
            XposedBridge.log(TAG + "SystemUI loaded; AOD handled in system_server");
        }
    }

    private static int readInt(Context context, String key, int fallback) {
        if (context == null) return fallback;
        try { return Settings.Global.getInt(context.getContentResolver(), key, fallback); }
        catch (Throwable ignored) { return fallback; }
    }
    private static Context fieldContext(Object object) {
        try { return (Context) XposedHelpers.getObjectField(object, "mContext"); }
        catch (Throwable ignored) { return null; }
    }
    private static float constrain(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /** Android brightness slider is gamma-encoded; approximate the AOSP slider conversion. */
    private static float sliderPercentToLinear(int percent) {
        final float x = constrain(percent / 100f, 0f, 1f);
        final float ret = x <= 0.5f ? (x / 0.5f) * (x / 0.5f)
                : (float) Math.exp((x - 0.55991073f) / 0.17883277f) + 0.28466892f;
        return constrain(ret / 12f, 0f, 1f);
    }

    private static void hookAutomaticBrightness(ClassLoader loader) {
        try {
            Class<?> abc = XposedHelpers.findClass(
                    "com.android.server.display.AutomaticBrightnessController", loader);
            XposedBridge.hookAllMethods(abc, "getAutomaticScreenBrightness", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof Float)) return;
                        float old = (Float) result;
                        if (old < 0 || Float.isNaN(old)) return; // invalid brightness sentinel
                        Context ctx = fieldContext(param.thisObject);
                        if (readInt(ctx, AUTO_ENABLED, 0) != 1) return;
                        // Do not override an explicit Doze policy, if ROM exposes the field.
                        try {
                            int state = XposedHelpers.getIntField(param.thisObject, "mDisplayState");
                            if (state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND) return;
                        } catch (Throwable ignored) { }
                        int cap = readInt(ctx, AUTO_CAP, 100);
                        float linearLimit = sliderPercentToLinear(Math.max(1, Math.min(100, cap)));
                        if (old > linearLimit) param.setResult(linearLimit);
                    } catch (Throwable t) { XposedBridge.log(TAG + "Auto hook: " + t); }
                }
            });
            XposedBridge.log(TAG + "auto brightness hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "Auto hook unavailable on this ROM: " + t);
        }
    }

    // AOD ONLY — receive doze brightness in the system_server DreamManagerService.
    // Automatic brightness logic above remains unchanged.
    private static int sAodAppliedLogs = 0;
    private static int sAodSkipLogs = 0;
    private static final float MIN_WORKING_DOZE = 0.01f;

    private static boolean validNormal(float v) {
        return Float.isFinite(v) && v > 0f && v <= 1f;
    }
    private static float savedNormalBrightness(Context ctx) {
        if (ctx == null) return -1f;
        try {
            float f = Settings.System.getFloat(ctx.getContentResolver(),
                    "screen_brightness_float", -1f);
            if (validNormal(f)) return f;
        } catch (Throwable ignored) { }
        try {
            int b = Settings.System.getInt(ctx.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            if (b > 0 && b <= 255) return b / 255f;
        } catch (Throwable ignored) { }
        return -1f;
    }

    private static float liveNormalBrightness(Context ctx) {
        if (ctx == null) return -1f;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return -1f;
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null || display.getState() != Display.STATE_ON) return -1f;
            Method method = dm.getClass().getMethod("getBrightness", int.class);
            Object v = method.invoke(dm, Display.DEFAULT_DISPLAY);
            if (v instanceof Number) {
                float value = ((Number) v).floatValue();
                if (validNormal(value)) return value;
            }
        } catch (Throwable ignored) { }
        return -1f;
    }

    private static void hookDreamManagerDoze(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.dreams.DreamManagerService", loader);
            int count = XposedBridge.hookAllMethods(manager, "startDozingInternal",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3) return;
                        Context ctx = fieldContext(param.thisObject);
                        if (ctx == null) {
                            if (++sAodSkipLogs <= 6)
                                XposedBridge.log(TAG + "AOD server skipped: no Context");
                            return;
                        }
                        if (readInt(ctx, AOD_ENABLED, 0) != 1) return;
                        if (param.args[1] instanceof Integer
                                && ((Integer) param.args[1]).intValue() == Display.STATE_OFF)
                            return;
                        int ratio = Math.max(1, Math.min(100, readInt(ctx, AOD_RATIO, 10)));
                        float normal = savedNormalBrightness(ctx);
                        String source = "saved";
                        if (!validNormal(normal)) {
                            normal = liveNormalBrightness(ctx);
                            source = "live";
                        }
                        if (!validNormal(normal)) {
                            if (++sAodSkipLogs <= 12)
                                XposedBridge.log(TAG + "AOD server skipped: normal brightness unavailable");
                            return;
                        }
                        float target = constrain(normal * ratio / 100f, MIN_WORKING_DOZE, 1f);
                        int brightnessIndex = -1;
                        boolean floating = false;
                        // Modern Android: token, state, reason, float, int, boolean.
                        for (int i = 2; i < param.args.length; i++) {
                            if (param.args[i] instanceof Float) {
                                brightnessIndex = i;
                                floating = true;
                                break;
                            }
                        }
                        // Legacy Android: token, state, intBrightness.
                        if (brightnessIndex == -1 && param.args.length == 3
                                && param.args[2] instanceof Integer) brightnessIndex = 2;
                        if (brightnessIndex == -1) {
                            if (++sAodSkipLogs <= 6)
                                XposedBridge.log(TAG + "AOD server skipped: unsupported args length="
                                        + param.args.length);
                            return;
                        }
                        Object previous = param.args[brightnessIndex];
                        Object replacement = floating ? Float.valueOf(target)
                                : Integer.valueOf(Math.max(1, Math.min(255, Math.round(target * 255f))));
                        param.args[brightnessIndex] = replacement;
                        if (++sAodAppliedLogs <= 40)
                            XposedBridge.log(TAG + "AOD server applied " + previous + " => "
                                    + replacement + " ratio=" + ratio + "% normal=" + normal
                                    + " source=" + source);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "AOD server hook error: " + t);
                    }
                }
            }).size();
            XposedBridge.log(TAG + "AOD DreamManager startDozingInternal hooks=" + count);
            if (count == 0) {
                for (Method method : manager.getDeclaredMethods()) {
                    if (method.getName().toLowerCase().contains("doz"))
                        XposedBridge.log(TAG + "AOD DreamManager method: " + method);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "AOD DreamManager hook unavailable: " + t);
        }
    }
}
