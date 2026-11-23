package dev.example.brightnesslink;

import android.content.Context;
import android.provider.Settings;
import android.hardware.display.DisplayManager;
import android.view.Display;

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
        if (("android".equals(lpparam.packageName) || "system".equals(lpparam.packageName))
                && "system_server".equals(lpparam.processName)) {
            hookAutomaticBrightness(lpparam.classLoader);
        }
        if ("com.android.systemui".equals(lpparam.packageName)
                && "com.android.systemui".equals(lpparam.processName)) {
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

    private static void hookDozeBrightness(ClassLoader loader) {
        try {
            Class<?> cls = XposedHelpers.findClass("com.android.systemui.doze.DozeScreenBrightness", loader);
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object old = param.getResult();
                        Context ctx = fieldContext(param.thisObject);
                        if (readInt(ctx, AOD_ENABLED, 0) != 1) return;
                        int pct = Math.max(1, Math.min(100, readInt(ctx, AOD_RATIO, 10)));
                        float ratio = pct / 100f;
                        DisplayManager dm = null;
                        try { dm = (DisplayManager) XposedHelpers.getObjectField(param.thisObject, "mDisplayManager"); }
                        catch (Throwable ignored) { }
                        if (dm == null && ctx != null) dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
                        float normal = dm == null ? -1f : dm.getBrightness(Display.DEFAULT_DISPLAY);
                        if (!Float.isFinite(normal) || normal <= 0f || normal > 1.0f) return;
                        // Mirrors AOSP's own source of current manual/auto brightness.
                        float target = constrain(normal * ratio, 0.005f, 1.0f);
                        if (old instanceof Float && ((Float)old) >= 0f) {
                            param.setResult(target);
                        } else if (old instanceof Integer && ((Integer)old) > 0) {
                            param.setResult(Math.max(1, Math.min(255, Math.round(target * 255f))));
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + "AOD hook: " + t); }
                }
            };
            // Both the sensor path and the default brightness path are covered.
            int hooked = 0;
            for (String method : new String[]{"clampToUserSettingFloat",
                    "clampToUserSettingOrAutoBrightnessFloat",
                    "clampToUserSetting", "clampToUserSettingOrAutoBrightness"}) {
                try { if (!XposedBridge.hookAllMethods(cls, method, hook).isEmpty()) hooked++; }
                catch (Throwable ignored) { }
            }
            XposedBridge.log(TAG + "Doze hooks installed: " + hooked + "/4 paths");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "AOD hooks unavailable on this ROM: " + t);
        }
    }
}
