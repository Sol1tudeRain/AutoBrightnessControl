package de.robv.android.xposed;
import java.util.Set; import java.util.HashSet;
public final class XposedBridge {public static Set<Object> hookAllMethods(Class<?> clz, String name, XC_MethodHook hook) {return new HashSet<>();} public static void log(String m) {}}
