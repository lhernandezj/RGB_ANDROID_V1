package com.secrethq.store;

import java.lang.ref.WeakReference;

import org.cocos2dx.lib.Cocos2dxActivity;

import android.app.ProgressDialog;
import android.util.Log;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.secrethq.store.util.*;

import kotlinx.coroutines.GlobalScope;


public class PTStoreBridge {
    private static boolean readyToPurchase = false;

    private static Cocos2dxActivity activity;
    private static WeakReference<Cocos2dxActivity> s_activity;
    private static final String TAG = "PTStoreBridge";

    private static native String licenseKey();

    public static native void purchaseDidComplete(String productId);

    public static native void purchaseDidCompleteRestoring(String productId);

    private static BillingDataSource s_billingDataSource;
    private static boolean inProgress = false;
    static public void initBridge(Cocos2dxActivity _activity) {
        Log.i(TAG, "PTStoreBridge -- INIT");
        activity = _activity;

        s_activity = new WeakReference<Cocos2dxActivity>(activity);
        s_billingDataSource = new BillingDataSource(activity, GlobalScope.INSTANCE);
        s_billingDataSource.initialize();
    }

    public static void beginLoadingProductDetails() {
        s_activity.get().runOnUiThread(() -> {
            s_billingDataSource.loadProductDetails(activity, (resultCode, message) -> {
                if (resultCode == s_billingDataSource.getBILLING_RESPONSE_RESULT_OK()) {
                    readyToPurchase = true;
                } else {
                    s_activity.get().runOnUiThread(() -> {
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                    });
                }
                return null;
            });
        });
    }

    static public void purchase(final String storeId, boolean isConsumable) {
        if (inProgress) {
            s_activity.get().runOnUiThread(() -> {
                Toast.makeText(activity, "An In-app purchase flow is already in progress.", Toast.LENGTH_SHORT).show();
            });
            return;
        }
        inProgress = true;
        s_activity.get().runOnUiThread(() -> {
            s_billingDataSource.launchBillingFlow(activity, storeId, isConsumable, (resultCode, message) -> {
                if (resultCode == s_billingDataSource.getBILLING_RESPONSE_RESULT_OK() ||
                        resultCode == s_billingDataSource.getBILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED()) {
                    purchaseDidComplete(storeId);
                } else {
                    s_activity.get().runOnUiThread(() -> {
                        if(message != null) {
                            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
                        }
                        else {
                            Toast.makeText(activity, "Unable to process the request. Please try again later.", Toast.LENGTH_SHORT).show();
                        }
                    });

                }
                inProgress = false;
                return null;
            });
        });
    }

    static public void restorePurchases() {
        s_activity.get().runOnUiThread(new Runnable() {
            public void run() {
                final ProgressDialog progress;
                progress = ProgressDialog.show(activity, null,
                        "Restoring purchases...", true);

                s_billingDataSource.restorePreviousIAPs(activity, (resultCode, message) -> {
                    if (resultCode == s_billingDataSource.getBILLING_RESPONSE_RESULT_OK()) {
                        purchaseDidCompleteRestoring(message);
                    }
                    else {
                        s_activity.get().runOnUiThread(() -> {
                            if (resultCode == s_billingDataSource.getBILLING_RESPONSE_RESULT_RESTORE_COMPLETED()) {
                                progress.dismiss();
                                Toast.makeText(activity, "Successfully restored all the purchases.", Toast.LENGTH_SHORT).show();
                            }
                            else {
                                progress.dismiss();
                                s_activity.get().runOnUiThread(() -> {
                                    Toast.makeText(activity, "Unable to restore purchases. Try again later.", Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                    }
                    return null;
                });
            }
        });
    }
}