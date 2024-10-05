package com.secrethq.utils;

import android.util.Log;

import org.cocos2dx.lib.Cocos2dxActivity;
import java.util.List;

public class PTJniHelper {
	public static String password() {
		return "0f0be4bf3faddd96a98e79c44878c08ffb9bd34b58378fced8d2c526ff4b028e";
	}

    public static native boolean isAdNetworkActive(String name);

    public static native String jsSettingsString();

    public static void setSettingsValue(String path, String value, Cocos2dxActivity act) {
        if (!Thread.currentThread().getName().contains("GLThread")) {
            act.runOnGLThread(() -> setSettingsValueNative(path, value));
        } else {
            setSettingsValueNative(path, value);
        }
    }

    public static String getSettingsValue(String path) {
        if (!Thread.currentThread().getName().contains("GLThread")) {
            Log.e("PTJniHelper", "getSettingsValue must be run inside runOnGLThread(new Runnable() {...}");
            throw new RuntimeException("getSettingsValue must be run inside runOnGLThread(new Runnable() {...}");
        } else {
            return getSettingsValueNative(path);
        }
    }

    private static native void setSettingsValueNative(String path, String value);
    private static native String getSettingsValueNative(String path);

    public static native boolean targetsChildren();

    public static native List<String> adNetworkSdkIds();

    public static native String classPath(String groupId, String sdkId);
    public static native String displayName(String groupId, String sdkId);
    public static native String privacyPolicyUrl(String groupId, String sdkId);
}
