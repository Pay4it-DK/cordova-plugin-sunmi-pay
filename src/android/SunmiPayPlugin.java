package com.sunmi.pay.cordova;

import android.os.Bundle;
import android.os.Build;
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

public class SunmiPayPlugin extends CordovaPlugin {

    private static final String TAG = "SunmiPayPlugin";
    
    private SunmiPayKernel mSMPayKernel;
    private ReadCardOptV2 mReadCardOptV2;
    private boolean isConnected = false;

    // 0x04 = Banking NFC (Credit Cards), 0x08 = General NFC (Mifare / ID Cards)
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

    /**
     * FIX: Handle App Pausing (Background)
     * Cancel any ongoing card reads to release the NFC hardware for other apps.
     */
    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        Log.d(TAG, "App Paused: Cancelling card check");
        cancelCheckCardInternal();
    }

    /**
     * FIX: Handle App Resuming (Foreground)
     * Ensure SDK is still connected.
     */
    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        Log.d(TAG, "App Resumed: Checking connection");
        if (mSMPayKernel == null || mReadCardOptV2 == null) {
            // Attempt silent reconnect if objects are lost
            connectSDK(null); 
        }
    }

    private void connectSDK(final CallbackContext callbackContext) {
        String manufacturer = Build.MANUFACTURER;
        if (manufacturer == null || !manufacturer.toUpperCase().contains("SUNMI")) {
            if(callbackContext != null) callbackContext.error("Not a Sunmi device");
            return;
        }

        try {
            if (mSMPayKernel == null) {
                mSMPayKernel = SunmiPayKernel.getInstance();
                mSMPayKernel.initPaySDK(cordova.getActivity().getApplicationContext(), new SunmiPayKernel.ConnectCallback() {
                    @Override
                    public void onConnectPaySDK() {
                        try {
                            mReadCardOptV2 = mSMPayKernel.mReadCardOptV2;
                            isConnected = true;
                            if(callbackContext != null) callbackContext.success("Connected");
                        } catch (Exception e) {
                             isConnected = false;
                             if(callbackContext != null) callbackContext.error("Connected but ReadCard module failed");
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
                 if(callbackContext != null) callbackContext.success("Already Connected");
            }
        } catch (Exception e) {
            if(callbackContext != null) callbackContext.error("Connection Failed: " + e.getMessage());
        }
    }

    private void checkCard(final CallbackContext callbackContext) {
        if (!isConnected || mReadCardOptV2 == null) {
            // Try to reconnect once
            connectSDK(null);
            if (!isConnected || mReadCardOptV2 == null) {
                callbackContext.error("SDK not connected");
                return;
            }
        }

        try {
            // FIX: Always cancel previous operation before starting a new one
            // This prevents the -20001 (Repeated call) error.
            mReadCardOptV2.cancelCheckCard();
            
            // Give a tiny delay for the cancel to register in the kernel
            try { Thread.sleep(50); } catch (InterruptedException ie) {}

            Log.d(TAG, "Starting checkCard Combined NFC");
            mReadCardOptV2.checkCard(CARD_TYPE_COMBINED_NFC, new CheckCardCallbackV2Wrapper(callbackContext), 60);

            PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
            result.setKeepCallback(true);
            callbackContext.sendPluginResult(result);

        } catch (RemoteException e) {
            callbackContext.error("Remote Exception: " + e.getMessage());
        }
    }

    // Exposed to JavaScript
    private void cancelCheckCard(CallbackContext callbackContext) {
        cancelCheckCardInternal();
        if(callbackContext != null) {
            callbackContext.success();
        }
    }

    // Internal usage (for onPause)
    private void cancelCheckCardInternal() {
        if (!isConnected || mReadCardOptV2 == null) return;
        try {
            mReadCardOptV2.cancelCheckCard();
        } catch (RemoteException e) {
            Log.e(TAG, "Error cancelling check card: " + e.getMessage());
        }
    }

    private class CheckCardCallbackV2Wrapper extends CheckCardCallbackV2.Stub {
        private CallbackContext callbackContext;
        private boolean isResultSent = false;

        CheckCardCallbackV2Wrapper(CallbackContext callbackContext) {
            this.callbackContext = callbackContext;
        }

        private synchronized void sendSuccess(JSONObject json) {
            if (isResultSent) return;
            isResultSent = true;
            PluginResult result = new PluginResult(PluginResult.Status.OK, json);
            callbackContext.sendPluginResult(result);
        }

        private synchronized void sendError(String message) {
            if (isResultSent) return;
            isResultSent = true;
            callbackContext.error(message);
        }

        @Override
        public void findMagCard(Bundle bundle) throws RemoteException { }
        @Override
        public void findICCard(String atr) throws RemoteException { }
        @Override
        public void findICCardEx(Bundle bundle) throws RemoteException { }

        @Override
        public void findRFCard(String uuid) throws RemoteException {
            Log.d(TAG, "findRFCard: " + uuid);
            sendNfcResult(uuid);
        }

        @Override
        public void findRFCardEx(Bundle bundle) throws RemoteException {
            Log.d(TAG, "findRFCardEx fired");
            String uuid = "";
            if (bundle != null) {
                if (bundle.containsKey("uuid")) uuid = bundle.getString("uuid");
                else if (bundle.containsKey("uid")) uuid = bundle.getString("uid");
            }
            sendNfcResult(uuid);
        }

        @Override
        public void onError(int code, String message) throws RemoteException {
            onErrorEx(null); // Fallback to Ex handler logic
        }

        @Override
        public void onErrorEx(Bundle bundle) throws RemoteException {
            int code = -1;
            String msg = "Unknown";
            if(bundle != null) {
                code = bundle.getInt("code", -1);
                msg = bundle.getString("msg", "Unknown");
            }
            
            // If the code is -20001, it means we called it too fast or didn't cancel the previous one.
            // We shouldn't crash the JS promise here, just log it.
            if (code == -20001) {
                Log.w(TAG, "Ignored -20001 error (Repeated Call) - waiting for active scan or user retry");
                return; 
            }

            Log.e(TAG, "onErrorEx Fired. Code: " + code + " | Msg: " + msg);

            // 1. Recover UUID if present
            if (bundle != null) {
                if(bundle.containsKey("uuid") || bundle.containsKey("uid")) {
                    String uuid = bundle.containsKey("uuid") ? bundle.getString("uuid") : bundle.getString("uid");
                    sendNfcResult(uuid);
                    return;
                }
            }
            
            // 2. Hardware fallback logic for payment cards
            if (code == -2549 || code == -2520) {
                 JSONObject json = new JSONObject();
                 try {
                     json.put("type", "NFC");
                     json.put("error", "NON_PAYMENT_CARD_DETECTED"); 
                     json.put("code", code);
                 } catch (JSONException e) {}
                 sendSuccess(json);
                 return;
            }

            sendError("Error: " + code + " - " + msg);
        }

        private void sendNfcResult(String uuid) {
            if (uuid == null) uuid = "";
            JSONObject json = new JSONObject();
            try {
                json.put("type", "NFC");
                json.put("uuid", uuid);
            } catch (JSONException e) {}
            sendSuccess(json);
        }
    }
    
    @Override
    public void onDestroy() {
        if(mSMPayKernel != null) {
            mSMPayKernel.destroyPaySDK();
        }
        super.onDestroy();
    }
}