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
        }
        if ("com.android.systemui".equals(lpparam.packageName)
                && "com.android.systemui".equals(lpparam.processName)) {
            XposedBridge.log(TAG + "SystemUI loaded; initializing AOD hooks");
            hookDozeBrightness(lpparam.classLoader);
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

    // ---------------- AOD ONLY (does not change the automatic brightness hook) ----------------
    // Keep the last NORMAL screen brightness, not the Doze brightness. The latter may be 0.
    private static volatile float sLastNormalBrightness = -1f;
    private static int sAodLogCount = 0;
    private static final float MIN_WORKING_DOZE = 0.01f; // confirmed with the user's old RRO

    private static Context contextOf(Object object) {
        if (object instanceof Context) return (Context) object;
        return fieldContext(object);
    }

    private static boolean validNormal(float v) {
        return Float.isFinite(v) && v > 0f && v <= 1f;
    }

    /** Public-API reading fallback; system_brightness_float is an Android system setting. */
    private static float savedNormalBrightness(Context ctx) {
        if (ctx == null) return -1f;
        try {
            float v = Settings.System.getFloat(
                    ctx.getContentResolver(), "screen_brightness_float", -1f);
            if (validNormal(v)) return v;
        } catch (Throwable ignored) { }
        try {
            int v = Settings.System.getInt(
                    ctx.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
            if (v > 0 && v <= 255) return v / 255f;
        } catch (Throwable ignored) { }
        return -1f;
    }

    /**
     * Read the current normal brightness before entering Doze. DisplayManager.getBrightness
     * is a hidden framework method: reflection prevents javac from needing that API.
     */
    private static float readLiveBrightness(Context ctx) {
        if (ctx == null) return -1f;
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return -1f;
            Method get = dm.getClass().getMethod("getBrightness", int.class);
            Object value = get.invoke(dm, Display.DEFAULT_DISPLAY);
            float f = value instanceof Number ? ((Number) value).floatValue() : -1f;
            return validNormal(f) ? f : -1f;
        } catch (Throwable ignored) { return -1f; }
    }

    private static void captureNormalBrightness(Context ctx) {
        float live = readLiveBrightness(ctx);
        if (!validNormal(live)) live = savedNormalBrightness(ctx);
        if (validNormal(live)) {
            sLastNormalBrightness = live;
            XposedBridge.log(TAG + "AOD captured pre-doze normal brightness=" + live);
        } else {
            XposedBridge.log(TAG + "AOD cannot capture normal brightness; using fallback");
        }
    }

    private static float targetAodBrightness(Context ctx) {
        if (readInt(ctx, AOD_ENABLED, 0) != 1) return -1f;
        int percent = Math.max(1, Math.min(100, readInt(ctx, AOD_RATIO, 10)));
        float normal = sLastNormalBrightness;
        // Don't call DisplayManager.getBrightness while dozing: it can return the AOD value.
        if (!validNormal(normal)) normal = savedNormalBrightness(ctx);
        if (!validNormal(normal)) return -1f;
        return constrain(normal * percent / 100f, MIN_WORKING_DOZE, 1f);
    }

    private static void hookDozeBrightness(ClassLoader loader) {
        // Cache actual brightness whenever SystemUI reads it while the display is ON.
        // Never cache Doze values, or the AOD ratio could feed back on itself.
        try {
            int cachedHooks = XposedBridge.hookAllMethods(DisplayManager.class,
                    "getBrightness", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1
                                || !(param.args[0] instanceof Integer)
                                || ((Integer) param.args[0]) != Display.DEFAULT_DISPLAY
                                || !(param.getResult() instanceof Float)) return;
                        float v = ((Float) param.getResult()).floatValue();
                        if (!validNormal(v)) return;
                        Display display = ((DisplayManager) param.thisObject)
                                .getDisplay(Display.DEFAULT_DISPLAY);
                        if (display != null && display.getState() == Display.STATE_ON) {
                            sLastNormalBrightness = v;
                        }
                    } catch (Throwable ignored) { }
                }
            }).size();
            XposedBridge.log(TAG + "AOD normal brightness cache hooks=" + cachedHooks);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "AOD normal brightness cache unavailable: " + t);
        }

        // Step 1: Make default Doze brightness nonzero BEFORE SystemUI sends its first request.
        // On this phone, a 0.01 framework Doze resource works, while 0.0 yields black AOD.
        try {
            Class<?> cls = XposedHelpers.findClass("com.android.systemui.doze.DozeScreenBrightness", loader);
            int count = XposedBridge.hookAllMethods(cls, "transitionTo", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) return;
                        if (!"INITIALIZED".equals(String.valueOf(param.args[1]))) return;
                        Context ctx = contextOf(param.thisObject);
                        if (readInt(ctx, AOD_ENABLED, 0) != 1) return;
                        // Prefer a last screen-ON sample, if SystemUI observed one.
                        if (!validNormal(sLastNormalBrightness)) captureNormalBrightness(ctx);
                        try {
                            Field f = param.thisObject.getClass().getDeclaredField(
                                    "mDefaultDozeBrightnessFloat");
                            f.setAccessible(true);
                            float old = f.getFloat(param.thisObject);
                            if (!Float.isFinite(old) || old < MIN_WORKING_DOZE) {
                                f.setFloat(param.thisObject, MIN_WORKING_DOZE);
                                XposedBridge.log(TAG + "AOD default Doze float raised from "
                                        + old + " to " + MIN_WORKING_DOZE);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "AOD float default field unavailable: " + t);
                        }
                        try {
                            Field f = param.thisObject.getClass().getDeclaredField(
                                    "mDefaultDozeBrightness");
                            f.setAccessible(true);
                            int old = f.getInt(param.thisObject);
                            if (old <= 0) f.setInt(param.thisObject, 2);
                        } catch (Throwable ignored) { }
                    } catch (Throwable t) { XposedBridge.log(TAG + "AOD Doze initialization: " + t); }
                }
            }).size();
            XposedBridge.log(TAG + "AOD DozeScreenBrightness.transitionTo hooks=" + count);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "AOD DozeScreenBrightness hook unavailable: " + t);
        }

        // Step 2: Hook the actual output interface, not only intermediate clamp methods.
        // Handles both modern float and legacy int Doze brightness paths in SystemUI.
        try {
            Class<?> service = XposedHelpers.findClass("com.android.systemui.doze.DozeService", loader);
            XC_MethodHook adjustFloat = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1
                                || !(param.args[0] instanceof Float)) return;
                        float target = targetAodBrightness(contextOf(param.thisObject));
                        if (target < 0f) return;
                        float old = ((Float) param.args[0]).floatValue();
                        param.args[0] = target;
                        if (++sAodLogCount <= 16) XposedBridge.log(TAG
                                + "AOD applied float " + old + " => " + target);
                    } catch (Throwable t) { XposedBridge.log(TAG + "AOD float output: " + t); }
                }
            };
            XC_MethodHook adjustInt = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1
                                || !(param.args[0] instanceof Integer)) return;
                        float target = targetAodBrightness(contextOf(param.thisObject));
                        if (target < 0f) return;
                        int old = ((Integer) param.args[0]).intValue();
                        int mapped = Math.max(1, Math.min(255, Math.round(target * 255f)));
                        param.args[0] = mapped;
                        if (++sAodLogCount <= 16) XposedBridge.log(TAG
                                + "AOD applied int " + old + " => " + mapped);
                    } catch (Throwable t) { XposedBridge.log(TAG + "AOD int output: " + t); }
                }
            };
            int f = XposedBridge.hookAllMethods(service,
                    "setDozeScreenBrightnessFloat", adjustFloat).size();
            int i = XposedBridge.hookAllMethods(service,
                    "setDozeScreenBrightness", adjustInt).size();
            XposedBridge.log(TAG + "AOD DozeService output hooks float=" + f + " int=" + i);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "AOD DozeService hooks unavailable: " + t);
        }
    }
}
