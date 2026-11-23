package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public static class MethodHookParam {public Object thisObject; public Object[] args; public Object getResult(){return null;} public void setResult(Object value) {}}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}
