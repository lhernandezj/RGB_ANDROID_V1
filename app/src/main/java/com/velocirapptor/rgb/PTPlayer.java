package com.velocirapptor.rgb;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.apponboard.aob_sessionreporting.AOBReporting;
import com.buildbox.AdIntegratorManager;
import com.buildbox.consent.ConsentActivity;
import com.buildbox.consent.ConsentHelper;
import com.buildbox.consent.SdkConsentInfo;
import com.google.android.gms.games.GamesActivityResultCodes;
import com.secrethq.store.PTStoreBridge;
import com.secrethq.utils.PTServicesBridge;

import org.cocos2dx.lib.Cocos2dxActivity;
import org.cocos2dx.lib.Cocos2dxGLSurfaceView;

import java.util.List;

public class PTPlayer extends Cocos2dxActivity {
    private boolean isPTStoreAvailable = false;
    static {
        System.loadLibrary("BBRuntime");
    }

    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        try {
            Log.v("----------", "onActivityResult: request: " + requestCode + " result: " + resultCode);
            if (requestCode == PTServicesBridge.RC_SIGN_IN) {
                SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sharedPref.edit();

                if (resultCode == RESULT_OK) {
                    PTServicesBridge.instance().onActivityResult(requestCode, resultCode, data);
                    editor.putBoolean("GooglePlayServiceSignInError", false);
                    editor.apply();
                } else if (resultCode == GamesActivityResultCodes.RESULT_SIGN_IN_FAILED) {
                    int duration = Toast.LENGTH_SHORT;
                    Toast toast = Toast.makeText(this, "Google Play Services: Sign in error", duration);
                    toast.show();
                    editor.putBoolean("GooglePlayServiceSignInError", true);
                    editor.apply();
                } else if (resultCode == GamesActivityResultCodes.RESULT_APP_MISCONFIGURED) {
                    int duration = Toast.LENGTH_SHORT;
                    Toast toast = Toast.makeText(this, "Google Play Services: App misconfigured", duration);
                    toast.show();
                }
            }
        } catch (Exception e) {
            Log.v("-----------", "onActivityResult FAILED: " + e.toString());
        }
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launchConsentActivity();
        this.hideVirtualButton();

        boolean isDebug = ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        if (!isDebug) {
            AOBReporting.initialize(this, getString(R.string.bb_version));
        }

        PTServicesBridge.initBridge(this, getString(R.string.app_id));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AdIntegratorManager.onActivityCreated(this);
    }

    private void launchConsentActivity() {
        if (!hasSeenConsentForAllSdks()) {
            Intent intent = new Intent(this, ConsentActivity.class);
            startActivity(intent);
            finish();
        }
    }

    private boolean hasSeenConsentForAllSdks() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        List<SdkConsentInfo> consentInfos = ConsentHelper.getSdkConsentInfos();
        for (SdkConsentInfo consentInfo : consentInfos) {
            if (!preferences.contains(ConsentHelper.getConsentKey(consentInfo.getSdkId()))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onNativeInit() {
        initBridges();
    }

    private void initBridges() {
        AdIntegratorManager.initBridge(this);
        PTStoreBridge.initBridge(this);
        isPTStoreAvailable = true;
    }

    @Override
    public Cocos2dxGLSurfaceView onCreateView() {
        Cocos2dxGLSurfaceView glSurfaceView = new Cocos2dxGLSurfaceView(this);
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 24, 0);

        return glSurfaceView;
    }

    @Override
    protected void onPause() {
        super.onPause();
        AdIntegratorManager.onActivityPaused(this);
    }

    @Override
    protected void onResume() {
        this.hideVirtualButton();
        super.onResume();
        AdIntegratorManager.onActivityResumed(this);
        // if (isPTStoreAvailable) {
        //     PTStoreBridge.acknowledgePendingPurchases();
        // }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            this.hideVirtualButton();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        AdIntegratorManager.onActivityStarted(this);
        AOBReporting.startOrResumeSessionReporting();
    }

    @Override
    protected void onStop() {
        super.onStop();
        AdIntegratorManager.onActivityStopped(this);
        AOBReporting.pauseSessionReporting();
    }

    @Override
    protected void onDestroy() {
        AdIntegratorManager.onActivityDestroyed(this);
        AOBReporting.stopSessionReporting();
        super.onDestroy();
    }

    protected void hideVirtualButton() {
        if (Build.VERSION.SDK_INT >= 19) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // hide nav bar
                    | View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
