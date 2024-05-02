/*
 * Copyright (C) 2018 The Android Open Source Project
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

/* Changes from Qualcomm Innovation Center, Inc. are provided under the following license:
 * Copyright (c) 2022-2024 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.settings.network.telephony;

import static android.telephony.ims.feature.ImsFeature.FEATURE_MMTEL;
import static android.telephony.ims.stub.ImsRegistrationImplBase.REGISTRATION_TECH_CROSS_SIM;

import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import android.content.Context;
import android.os.RemoteException;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;

import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import com.android.settings.R;
import com.android.settings.flags.Flags;
import com.android.settings.network.MobileNetworkRepository;
import com.android.settings.wifi.WifiPickerTrackerHelper;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.mobile.dataservice.MobileNetworkInfoEntity;
import com.android.settingslib.mobile.dataservice.SubscriptionInfoEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Preference controller for "Mobile data"
 */
public class MobileDataPreferenceController extends TelephonyTogglePreferenceController
        implements LifecycleObserver, MobileNetworkRepository.MobileNetworkCallback {

    private static final String DIALOG_TAG = "MobileDataDialog";
    private static final String TAG = "MobileDataPreferenceController";

    private TwoStatePreference mPreference;
    private TelephonyManager mTelephonyManager;
    private SubscriptionManager mSubscriptionManager;
    private FragmentManager mFragmentManager;
    @VisibleForTesting
    int mDialogType;
    @VisibleForTesting
    boolean mNeedDialog;

    private WifiPickerTrackerHelper mWifiPickerTrackerHelper;
    protected MobileNetworkRepository mMobileNetworkRepository;
    protected LifecycleOwner mLifecycleOwner;
    private List<SubscriptionInfoEntity> mSubscriptionInfoEntityList = new ArrayList<>();
    private List<MobileNetworkInfoEntity> mMobileNetworkInfoEntityList = new ArrayList<>();
    private int mDefaultSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    SubscriptionInfoEntity mSubscriptionInfoEntity;
    MobileNetworkInfoEntity mMobileNetworkInfoEntity;
    private DdsDataOptionStateTuner mDdsDataOptionStateTuner;
    private SparseBooleanArray mIsSubInCall;
    private SparseBooleanArray mIsCiwlanModeSupported;
    private SparseBooleanArray mIsCiwlanEnabled;
    private SparseBooleanArray mIsInCiwlanOnlyMode;
    private SparseBooleanArray mIsImsRegisteredOnCiwlan;

    public MobileDataPreferenceController(Context context, String key, Lifecycle lifecycle,
            LifecycleOwner lifecycleOwner, int subId) {
        super(context, key);
        mSubId = subId;
        mSubscriptionManager = context.getSystemService(SubscriptionManager.class);
        mMobileNetworkRepository = MobileNetworkRepository.getInstance(context);
        mLifecycleOwner = lifecycleOwner;
        if (lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public int getAvailabilityStatus(int subId) {
        if (Flags.isDualSimOnboardingEnabled()) {
            return CONDITIONALLY_UNAVAILABLE;
        }
        return subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID
                ? AVAILABLE
                : AVAILABLE_UNSEARCHABLE;
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
    }

    @OnLifecycleEvent(ON_START)
    public void onStart() {
        mMobileNetworkRepository.addRegister(mLifecycleOwner, this, mSubId);
        mMobileNetworkRepository.updateEntity();
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            // Register for nDDS sub events. What happens to the mobile data toggle in case
            // of a voice call is dependent on the device being in temp DDS state which is
            // checked in updateState()
            mDdsDataOptionStateTuner.register(mContext, mSubId);
        }
    }

    @OnLifecycleEvent(ON_STOP)
    public void onStop() {
        mMobileNetworkRepository.removeRegister(this);
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            mDdsDataOptionStateTuner.unregister(mContext);
        }
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (TextUtils.equals(preference.getKey(), getPreferenceKey())) {
            if (mNeedDialog) {
                showDialog(mDialogType);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean setChecked(boolean isChecked) {
        mNeedDialog = isDialogNeeded();

        if (!mNeedDialog) {
            // Update data directly if we don't need dialog
            Log.d(DIALOG_TAG, "setMobileDataEnabled: " + isChecked);
            MobileNetworkUtils.setMobileDataEnabled(mContext, mSubId, isChecked, false);
            if (mWifiPickerTrackerHelper != null
                    && !mWifiPickerTrackerHelper.isCarrierNetworkProvisionEnabled(mSubId)) {
                mWifiPickerTrackerHelper.setCarrierNetworkEnabled(isChecked);
            }
            return true;
        }

        return false;
    }

    @Override
    public boolean isChecked() {
        return mMobileNetworkInfoEntity == null ? false
                : mMobileNetworkInfoEntity.isMobileDataEnabled;
    }

    @Override
    public void updateState(Preference preference) {
        if (mTelephonyManager == null) {
            return;
        }
        super.updateState(preference);
        mPreference = (TwoStatePreference) preference;
        update();
    }

    private void update() {

        if (mSubscriptionInfoEntity == null || mPreference == null) {
            return;
        }

        mPreference.setChecked(isChecked());
        if (mSubscriptionInfoEntity.isOpportunistic) {
            mPreference.setEnabled(false);
            mPreference.setSummary(R.string.mobile_data_settings_summary_auto_switch);
        } else {
            if (mDdsDataOptionStateTuner.isDisallowed()) {
                Log.d(TAG, "nDDS voice call in ongoing");
                // we will get inside this block only when the current instance is for the DDS
                if (isChecked()) {
                    Log.d(TAG, "Do not allow the user to turn off DDS mobile data");
                    mPreference.setEnabled(false);
                    boolean isSmartDdsEnabled =
                            Settings.Global.getInt(mContext.getContentResolver(),
                            Settings.Global.SMART_DDS_SWITCH, 0) == 1;
                    if (isSmartDdsEnabled) {
                        mPreference.setSummary(
                                R.string.mobile_data_settings_summary_on_smart_dds_unavailable);
                    } else {
                        mPreference.setSummary(
                                R.string.mobile_data_settings_summary_default_data_unavailable);
                    }
                }
            } else {
                if (TelephonyUtils.isSubsidyFeatureEnabled(mContext) &&
                        !TelephonyUtils.isSubsidySimCard(mContext,
                        mSubscriptionManager.getSlotIndex(mSubId))) {
                    mPreference.setEnabled(false);
                } else {
                    mPreference.setEnabled(true);
                }
                mPreference.setSummary(R.string.mobile_data_settings_summary);
            }
        }
        if (!mSubscriptionInfoEntity.isValidSubscription) {
            mPreference.setSelectable(false);
            mPreference.setSummary(R.string.mobile_data_settings_summary_unavailable);
        } else {
            mPreference.setSelectable(true);
        }
    }

    public void init(FragmentManager fragmentManager, int subId,
            SubscriptionInfoEntity subInfoEntity, MobileNetworkInfoEntity networkInfoEntity) {
        mFragmentManager = fragmentManager;
        mSubId = subId;
        mTelephonyManager = null;
        mTelephonyManager = getTelephonyManager();
        mSubscriptionInfoEntity = subInfoEntity;
        mMobileNetworkInfoEntity = networkInfoEntity;
        mDdsDataOptionStateTuner =
                new DdsDataOptionStateTuner(mTelephonyManager,
                        mSubscriptionManager,
                        () -> updateState(mPreference));
    }

    private TelephonyManager getTelephonyManager() {
        if (mTelephonyManager != null) {
            return mTelephonyManager;
        }
        TelephonyManager telMgr =
                mContext.getSystemService(TelephonyManager.class);
        if (mSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            telMgr = telMgr.createForSubscriptionId(mSubId);
        }
        mTelephonyManager = telMgr;
        return telMgr;
    }

    public void setWifiPickerTrackerHelper(WifiPickerTrackerHelper helper) {
        mWifiPickerTrackerHelper = helper;
    }

    @VisibleForTesting
    boolean isDialogNeeded() {
        final boolean enableData = !isChecked();
        mTelephonyManager = getTelephonyManager();
        final boolean isMultiSim = (mTelephonyManager.getActiveModemCount() > 1);
        boolean needToDisableOthers = mDefaultSubId != mSubId;
        if (mContext.getResources().getBoolean(
                 com.android.internal.R.bool.config_voice_data_sms_auto_fallback)) {
            // Mobile data of both subscriptions can be enabled
            // simultaneously. DDS setting will be controlled by the config.
            needToDisableOthers = false;
        }
        Log.d(TAG, "isDialogNeeded: enableData = " + enableData  + ", isMultiSim = " + isMultiSim +
                ", needToDisableOthers = " + needToDisableOthers);
        if (enableData && isMultiSim && needToDisableOthers) {
            mDialogType = MobileDataDialogFragment.TYPE_MULTI_SIM_DIALOG;
            return true;
        }
        if (!enableData) {
            final int DDS = SubscriptionManager.getDefaultDataSubscriptionId();
            final int nDDS = MobileNetworkSettings.getNonDefaultDataSub();

            // Store the call state and C_IWLAN-related settings of all active subscriptions
            int[] activeSubIdList = mSubscriptionManager.getActiveSubscriptionIdList();
            mIsSubInCall = new SparseBooleanArray(activeSubIdList.length);
            mIsCiwlanModeSupported = new SparseBooleanArray(activeSubIdList.length);
            mIsCiwlanEnabled = new SparseBooleanArray(activeSubIdList.length);
            mIsInCiwlanOnlyMode = new SparseBooleanArray(activeSubIdList.length);
            mIsImsRegisteredOnCiwlan = new SparseBooleanArray(activeSubIdList.length);
            for (int i = 0; i < activeSubIdList.length; i++) {
                int subId = activeSubIdList[i];
                TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
                mIsSubInCall.put(subId, tm.getCallStateForSubscription() !=
                        TelephonyManager.CALL_STATE_IDLE);
                mIsCiwlanModeSupported.put(subId, MobileNetworkSettings.isCiwlanModeSupported(
                        subId));
                mIsCiwlanEnabled.put(subId, MobileNetworkSettings.isCiwlanEnabled(subId));
                mIsInCiwlanOnlyMode.put(subId, MobileNetworkSettings.isInCiwlanOnlyMode(subId));
                mIsImsRegisteredOnCiwlan.put(subId, MobileNetworkSettings.isImsRegisteredOnCiwlan(
                        subId));
            }

            // For targets that support MSIM C_IWLAN, the warning is to be shown only for the DDS
            // when either sub is in a call. For other targets, it will be shown only when there is
            // a call on the DDS.
            boolean isMsimCiwlanSupported = MobileNetworkSettings.isMsimCiwlanSupported();
            int subToCheck = DDS;
            if (isMsimCiwlanSupported) {
                if (mSubId != DDS) {
                    // If the code comes here, the user is trying to change the mobile data toggle
                    // of the nDDS which we don't care about.
                    return false;
                } else {
                    // Otherwise, the user is trying to toggle the mobile data of the DDS. In this
                    // case, we need to check if the nDDS is in a call. If it is, we will check the
                    // C_IWLAN related settings belonging to the nDDS. Otherwise, we will check
                    // those of the DDS.
                    subToCheck = subToCheckForCiwlanWarningDialog(nDDS, DDS);
                    Log.d(TAG, "isDialogNeeded DDS = " + DDS + ", subToCheck = " + subToCheck);
                }
            }

            if (mIsSubInCall.get(subToCheck)) {
                boolean isCiwlanModeSupported = mIsCiwlanModeSupported.get(subToCheck);
                boolean isCiwlanEnabled = mIsCiwlanEnabled.get(subToCheck);
                boolean isInCiwlanOnlyMode = mIsInCiwlanOnlyMode.get(subToCheck);
                boolean isImsRegisteredOnCiwlan = mIsImsRegisteredOnCiwlan.get(subToCheck);
                if (isCiwlanEnabled && (isInCiwlanOnlyMode || !isCiwlanModeSupported)) {
                    Log.d(TAG, "isDialogNeeded: isInCall = true, isCiwlanEnabled = true" +
                            ", isInCiwlanOnlyMode = " + isInCiwlanOnlyMode +
                            ", isCiwlanModeSupported = " + isCiwlanModeSupported +
                            ", isImsRegisteredOnCiwlan = " + isImsRegisteredOnCiwlan);
                    // If IMS is registered over C_IWLAN-only mode, the device is in a call, and
                    // user is trying to disable mobile data, display a warning dialog that
                    // disabling mobile data will cause a call drop.
                    if (isImsRegisteredOnCiwlan) {
                        mDialogType = MobileDataDialogFragment.TYPE_DISABLE_CIWLAN_DIALOG;
                        return true;
                    }
                } else {
                    Log.d(TAG, "isDialogNeeded: C_IWLAN not enabled or not in C_IWLAN-only mode");
                }
            } else {
                Log.d(TAG, "isDialogNeeded: Not in a call");
            }
        }
        return false;
    }

    private int subToCheckForCiwlanWarningDialog(int ndds, int dds) {
        int subToCheck = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        if (mIsSubInCall.get(ndds) && mIsCiwlanEnabled.get(ndds) &&
                (mIsInCiwlanOnlyMode.get(ndds) || !mIsCiwlanModeSupported.get(ndds)) &&
                mIsImsRegisteredOnCiwlan.get(ndds)) {
            subToCheck = ndds;
        } else {
            subToCheck = dds;
        }
        return subToCheck;
    }

    private void showDialog(int type) {
        final MobileDataDialogFragment dialogFragment = MobileDataDialogFragment.newInstance(
                mPreference.getTitle().toString(), type, mSubId,
                MobileNetworkSettings.isCiwlanModeSupported(mSubId));
        dialogFragment.show(mFragmentManager, DIALOG_TAG);
    }

    @VisibleForTesting
    public void setSubscriptionInfoEntity(SubscriptionInfoEntity subscriptionInfoEntity) {
        mSubscriptionInfoEntity = subscriptionInfoEntity;
    }

    @VisibleForTesting
    public void setMobileNetworkInfoEntity(MobileNetworkInfoEntity mobileNetworkInfoEntity) {
        mMobileNetworkInfoEntity = mobileNetworkInfoEntity;
    }

    @Override
    public void onActiveSubInfoChanged(List<SubscriptionInfoEntity> subInfoEntityList) {
        mSubscriptionInfoEntityList = subInfoEntityList;
        mSubscriptionInfoEntityList.forEach(entity -> {
            if (entity.getSubId() == mSubId) {
                mSubscriptionInfoEntity = entity;
                if (entity.getSubId() == SubscriptionManager.getDefaultDataSubscriptionId()) {
                    mDefaultSubId = entity.getSubId();
                }
            }
        });

        update();
        refreshSummary(mPreference);
    }


    @Override
    public void onAllMobileNetworkInfoChanged(
            List<MobileNetworkInfoEntity> mobileNetworkInfoEntityList) {
        mMobileNetworkInfoEntityList = mobileNetworkInfoEntityList;
        mMobileNetworkInfoEntityList.forEach(entity -> {
            if (Integer.parseInt(entity.subId) == mSubId) {
                mMobileNetworkInfoEntity = entity;
                update();
                refreshSummary(mPreference);
                return;
            }
        });
    }
}
