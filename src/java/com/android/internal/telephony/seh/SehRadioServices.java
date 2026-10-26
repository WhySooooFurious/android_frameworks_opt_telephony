/*
 * Copyright (C) 2026 The PixelOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.seh;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.RIL;
import com.android.internal.telephony.uicc.UiccController;

import vendor.samsung.hardware.radio.SehRadioResponseInfo;
import vendor.samsung.hardware.radio.sim.ISehRadioSim;
import vendor.samsung.hardware.radio.sim.ISehRadioSimIndication;
import vendor.samsung.hardware.radio.sim.ISehRadioSimResponse;
import vendor.samsung.hardware.radio.sim.SehCardStatus;
import vendor.samsung.hardware.radio.sim.SehPhonebookInfo;
import vendor.samsung.hardware.radio.sim.SehSimLockInfo;
import vendor.samsung.hardware.radio.sim.SehSimPhonebookResponse;
import vendor.samsung.hardware.radio.sim.SehSsReleaseComplete;

/**
 * Binds the Samsung SEH radio SIM HAL service
 * (vendor.samsung.hardware.radio.sim) and forwards SIM card status to
 * {@link UiccController}.
 *
 * <p>The Samsung rild fills SIM indication pointers only through the AIDL
 * {@code setResponseFunctions(response, indication)} callback on its per-slot
 * vendor HAL services. Without this binding, UiccController never receives a
 * SIM state and the device reports "no SIM" despite a valid card.
 */
public class SehRadioServices {
    private static final String TAG = "SehRadioServices";
    private static final int MAX_BIND_ATTEMPTS = 40;
    private static final long BIND_RETRY_MS = 500;
    private static final String SIM_SERVICE = "vendor.samsung.hardware.radio.sim.ISehRadioSim/slot";

    private static volatile ISehRadioSim sSim;

    public static void init(RIL ril, int phoneId) {
        bind(phoneId, 0);
    }

    private static void bind(int phoneId, int attempt) {
        int slot = phoneId + 1;
        String serviceName = SIM_SERVICE + slot;
        IBinder binder = ServiceManager.getService(serviceName);
        if (binder == null) {
            if (attempt < MAX_BIND_ATTEMPTS) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> bind(phoneId, attempt + 1), BIND_RETRY_MS);
            } else {
                Log.w(TAG, "SEH sim service not available: " + serviceName
                        + " (phoneId=" + phoneId + ")");
            }
            return;
        }
        try {
            ISehRadioSim sim = ISehRadioSim.Stub.asInterface(binder);
            sSim = sim;
            sim.setResponseFunctions(new ResponseStub(phoneId), new IndicationStub(phoneId));
            sim.getIccCardStatus(slot);
            Log.i(TAG, "SEH sim bound: phoneId=" + phoneId + " slot=" + slot);
        } catch (RemoteException e) {
            Log.e(TAG, "SEH sim bind failed: phoneId=" + phoneId, e);
        }
    }

    private static void onSimStatus(int phoneId, SehCardStatus cs) {
        if (cs == null) {
            Log.w(TAG, "onSimStatus: null card status (phoneId=" + phoneId + ")");
            return;
        }
        IccCardConstants.State state = mapState(cs.cardState);
        try {
            UiccController.getInstance().updateSimState(phoneId, state, "SEH rild");
            Log.i(TAG, "updateSimState: phoneId=" + phoneId + " cardState=" + cs.cardState
                    + " state=" + state + " iccid=" + cs.iccid);
        } catch (Exception e) {
            Log.e(TAG, "updateSimState failed: phoneId=" + phoneId, e);
        }
    }

    /** Maps a rild RIL_CARD_* value to the framework SIM state. */
    private static IccCardConstants.State mapState(int cardState) {
        switch (cardState) {
            case 0:
                return IccCardConstants.State.NOT_READY;
            case 1:
                return IccCardConstants.State.ABSENT;
            case 2:
            case 6:
                return IccCardConstants.State.READY;
            case 3:
                return IccCardConstants.State.PIN_REQUIRED;
            case 4:
                return IccCardConstants.State.PUK_REQUIRED;
            case 5:
                return IccCardConstants.State.CARD_IO_ERROR;
            default:
                return IccCardConstants.State.UNKNOWN;
        }
    }

    private static void requery(int phoneId) {
        ISehRadioSim sim = sSim;
        if (sim == null) {
            return;
        }
        try {
            sim.getIccCardStatus(phoneId + 1);
        } catch (RemoteException e) {
            Log.e(TAG, "requery failed: phoneId=" + phoneId, e);
        }
    }

    private static class ResponseStub extends ISehRadioSimResponse.Stub {
        private final int mPhoneId;

        ResponseStub(int phoneId) {
            mPhoneId = phoneId;
        }

        @Override
        public void getIccCardStatusResponse(SehRadioResponseInfo info, SehCardStatus cardStatus) {
            onSimStatus(mPhoneId, cardStatus);
        }

        @Override
        public void accessPhonebookEntryResponse(SehRadioResponseInfo info, int arg) {
        }

        @Override
        public void changeIccPersonalizationResponse(SehRadioResponseInfo info) {
        }

        @Override
        public void getAtrResponse(SehRadioResponseInfo info, String atr) {
        }

        @Override
        public String getInterfaceHash() {
            return "";
        }

        @Override
        public int getInterfaceVersion() {
            return 1;
        }

        @Override
        public void getPhonebookEntryResponse(SehRadioResponseInfo info,
                SehSimPhonebookResponse resp) {
        }

        @Override
        public void getPhonebookStorageInfoResponse(SehRadioResponseInfo info,
                SehPhonebookInfo phonebookInfo) {
        }

        @Override
        public void getSimLockInfoResponse(SehRadioResponseInfo info,
                SehSimLockInfo lockInfo) {
        }

        @Override
        public void getUsimPhonebookCapabilityResponse(SehRadioResponseInfo info, int[] caps) {
        }

        @Override
        public void setSimInitEventResponse(SehRadioResponseInfo info) {
        }

        @Override
        public void setSimOnOffResponse(SehRadioResponseInfo info) {
        }

        @Override
        public void supplyIccPersonalizationResponse(SehRadioResponseInfo info) {
        }

        @Override
        public void supplyNetworkDepersonalizationResponse(SehRadioResponseInfo info, int arg) {
        }
    }

    private static class IndicationStub extends ISehRadioSimIndication.Stub {
        private final int mPhoneId;

        IndicationStub(int phoneId) {
            mPhoneId = phoneId;
        }

        @Override
        public void simOnOffStateChangedNotify(int indicationType, int mode) {
            requery(mPhoneId);
        }

        @Override
        public void simSwapStateChangedIndication(int indicationType, int arg) {
            requery(mPhoneId);
        }

        @Override
        public String getInterfaceHash() {
            return "";
        }

        @Override
        public int getInterfaceVersion() {
            return 1;
        }

        @Override
        public void phonebookInitCompleteIndication(int arg) {
        }

        @Override
        public void releaseCompleteMessageIndication(int arg, SehSsReleaseComplete release) {
        }

        @Override
        public void sapNotify(int arg, byte[] data) {
        }

        @Override
        public void simCountMismatchedIndication(int arg, int arg2) {
        }

        @Override
        public void simPhonebookReadyIndication(int arg) {
        }

        @Override
        public void stkCallControlResultIndication(int arg, String result) {
        }

        @Override
        public void stkSmsSendResultIndication(int arg, int arg2) {
        }
    }
}
