package com.sunmi.pay.cordova;

import android.os.Bundle;
import android.os.Build; // Added import for device info
import android.os.RemoteException;
import android.util.Log;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import sunmi.paylib.SunmiPayKernel;
import com.sunmi.pay.hardware.aidlv2.readcard.ReadCardOptV2;
import com.sunmi.pay.hardware.aidlv2.readcard.CheckCardCallbackV2;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

public class SunmiPayPlugin extends CordovaPlugin {

    private static final String TAG = "SunmiPayPlugin";

    private SunmiPayKernel mSMPayKernel;
    private ReadCardOptV2 mReadCardOptV2;
    private boolean isConnected = false;

    // --- CONFIGURATION ---
    // 0x04 = Banking NFC (Credit Cards)
    // 0x08 = General NFC (Mifare / ID Cards)
    // We combine them to support BOTH types.
    private static final int CARD_TYPE_COMBINED_NFC = 0x04 | 0x08;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (action.equals("connect")) {
            this.connectSDK(callbackContext);
            return true;
        } else if (action.equals("checkCard")) {
            this.checkCard(callbackContext);
            return true;
        } else if (action.equals("cancelCheckCard")) {
            this.cancelCheckCard(callbackContext);
            return true;
        }
        return false;
    }

    private void connectSDK(final CallbackContext callbackContext) {
        // 1. Check if the underlying Hardware Service supports Financial Payments
        // If not (e.g. V2s, CS20), we fail immediately so JS falls back to Standard
        // NFC.
        if (!isFinancialHardware()) {
            Log.e(TAG, "Device has low/missing PayHardwareService. Aborting Sunmi SDK.");
            callbackContext.error("Non-Financial Sunmi Device Detected");
            return;
        }

        // 2. Proceed with connection
        try {
            if (mSMPayKernel == null) {
                mSMPayKernel = SunmiPayKernel.getInstance();
                mSMPayKernel.initPaySDK(cordova.getActivity().getApplicationContext(),
                        new SunmiPayKernel.ConnectCallback() {
                            @Override
                            public void onConnectPaySDK() {
                                try {
                                    mReadCardOptV2 = mSMPayKernel.mReadCardOptV2;
                                    // Double check if module loaded
                                    if (mReadCardOptV2 == null) {
                                        isConnected = false;
                                        callbackContext.error("Connected but ReadCard module is null");
                                        return;
                                    }
                                    isConnected = true;
                                    callbackContext.success("Connected");
                                } catch (Exception e) {
                                    isConnected = false;
                                    callbackContext.error("Connected but ReadCard module failed");
                                }
                            }

                            @Override
                            public void onDisconnectPaySDK() {
                                isConnected = false;
                                mReadCardOptV2 = null;
                            }
                        });
            } else {
                isConnected = true;
                mReadCardOptV2 = mSMPayKernel.mReadCardOptV2;
                callbackContext.success("Already Connected");
            }
        } catch (Exception e) {
            callbackContext.error("Connection Failed: " + e.getMessage());
        }
    }

    private void checkCard(final CallbackContext callbackContext) {
        if (!isConnected || mReadCardOptV2 == null) {
            callbackContext.error("SDK not connected");
            return;
        }

        try {
            // COMBINED SCAN (0x0C)
            int cardType = CARD_TYPE_COMBINED_NFC;

            Log.d(TAG, "Starting checkCard Combined NFC (0x04 | 0x08)");

            mReadCardOptV2.checkCard(cardType, new CheckCardCallbackV2Wrapper(callbackContext), 60);

            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);

        } catch (RemoteException e) {
            callbackContext.error("Remote Exception: " + e.getMessage());
        }
    }

    private void cancelCheckCard(CallbackContext callbackContext) {
        if (!isConnected || mReadCardOptV2 == null)
            return;
        try {
            mReadCardOptV2.cancelCheckCard();
            callbackContext.success();
        } catch (RemoteException e) {
            callbackContext.error(e.getMessage());
        }
    }

    private class CheckCardCallbackV2Wrapper extends CheckCardCallbackV2.Stub {
        private CallbackContext callbackContext;
        private boolean isResultSent = false;

        CheckCardCallbackV2Wrapper(CallbackContext callbackContext) {
            this.callbackContext = callbackContext;
        }

        private synchronized void sendSuccess(JSONObject json) {
            if (isResultSent)
                return;
            isResultSent = true;
            PluginResult result = new PluginResult(PluginResult.Status.OK, json);
            callbackContext.sendPluginResult(result);
        }

        private synchronized void sendError(String message) {
            if (isResultSent)
                return;
            isResultSent = true;
            callbackContext.error(message);
        }

        // Mag and IC are disabled to prevent P3H power crash (-12549)
        @Override
        public void findMagCard(Bundle bundle) throws RemoteException {
        }

        @Override
        public void findICCard(String atr) throws RemoteException {
        }

        @Override
        public void findICCardEx(Bundle bundle) throws RemoteException {
        }

        @Override
        public void findRFCard(String uuid) throws RemoteException {
            Log.d(TAG, "findRFCard: " + uuid);
            sendNfcResult(uuid);
        }

        @Override
        public void findRFCardEx(Bundle bundle) throws RemoteException {
            Log.d(TAG, "findRFCardEx fired");
            logFullBundle(bundle);

            String uuid = "";
            if (bundle != null) {
                if (bundle.containsKey("uuid"))
                    uuid = bundle.getString("uuid");
                else if (bundle.containsKey("uid"))
                    uuid = bundle.getString("uid");
            }
            sendNfcResult(uuid);
        }

        @Override
        public void onError(int code, String message) throws RemoteException {
            onErrorEx(null);
        }

        @Override
        public void onErrorEx(Bundle bundle) throws RemoteException {
            // LOGGING
            int code = -1;
            String msg = "Unknown";
            if (bundle != null) {
                code = bundle.getInt("code", -1);
                msg = bundle.getString("msg", "Unknown");
            }
            Log.e(TAG, "onErrorEx Fired. Code: " + code + " | Msg: " + msg);
            logFullBundle(bundle);

            if (code == -1)
                return; // Ignore generic

            // 1. Recover UUID if present
            if (bundle != null) {
                if (bundle.containsKey("uuid") || bundle.containsKey("uid")) {
                    String uuid = bundle.containsKey("uuid") ? bundle.getString("uuid") : bundle.getString("uid");
                    Log.d(TAG, "Recovered UUID from Error: " + uuid);
                    sendNfcResult(uuid);
                    return;
                }
            }

            // 2. Fallback: If 0x08 fails to catch the card, and 0x04 throws error
            if (code == -2549 || code == -2520) {
                JSONObject json = new JSONObject();
                try {
                    json.put("type", "NFC");
                    json.put("error", "NON_PAYMENT_CARD_DETECTED");
                    json.put("code", code);
                } catch (JSONException e) {
                }
                sendSuccess(json);
                return;
            }

            sendError("Error: " + code + " - " + msg);
        }

        private void sendNfcResult(String uuid) {
            if (uuid == null)
                uuid = "";
            JSONObject json = new JSONObject();
            try {
                json.put("type", "NFC");
                json.put("uuid", uuid);
            } catch (JSONException e) {
            }
            sendSuccess(json);
        }

        private void logFullBundle(Bundle b) {
            if (b == null)
                return;
            Log.d(TAG, ">>> DUMPING BUNDLE DATA:");
            for (String key : b.keySet()) {
                Object value = b.get(key);
                String valueStr = (value != null) ? value.toString() : "null";
                Log.d(TAG, "Key: [" + key + "] Value: [" + valueStr + "]");
            }
            Log.d(TAG, ">>> END DUMP");
        }
    }

    /**
     * Checks if the Sunmi Pay Hardware Service is installed and is a high enough
     * version
     * to support the Financial Card Reader (P-Series).
     * V2s/CS20 will have version ~52. P3/P2 will have version > 3000.
     */
    private boolean isFinancialHardware() {
        try {
            PackageManager pm = cordova.getActivity().getPackageManager();
            // The package name used by Sunmi PayHardwareService
            PackageInfo pInfo = pm.getPackageInfo("com.sunmi.pay.hardware_v3", 0);

            Log.d(TAG, "Sunmi Pay Service Version Code: " + pInfo.versionCode);

            // The log stated: "please upgrade to v3.3.300+ version"
            // We treat anything below 300 as "Lite/Printer Only" mode (V2s/CS20)
            if (pInfo.versionCode < 300) {
                return false;
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            // Service not found = Not a Sunmi P-Series device
            return false;
        }
    }

    @Override
    public void onDestroy() {
        if (mSMPayKernel != null) {
            mSMPayKernel.destroyPaySDK();
        }
        super.onDestroy();
    }
}