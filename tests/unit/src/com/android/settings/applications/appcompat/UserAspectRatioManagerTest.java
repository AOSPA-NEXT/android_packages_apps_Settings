/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settings.applications.appcompat;

import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_16_9;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_4_3;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_SPLIT_SCREEN;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_UNSET;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE;

import static com.android.settings.applications.appcompat.UserAspectRatioManager.KEY_ENABLE_USER_ASPECT_RATIO_FULLSCREEN;
import static com.android.settings.applications.appcompat.UserAspectRatioManager.KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.provider.DeviceConfig;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.R;
import com.android.settings.testutils.ResourcesUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * To run this test: atest SettingsUnitTests:UserAspectRatioManagerTest
 */
@RunWith(AndroidJUnit4.class)
public class UserAspectRatioManagerTest {

    private Context mContext;
    private Resources mResources;
    private UserAspectRatioManager mUtils;
    private String mOriginalSettingsFlag;
    private String mOriginalFullscreenFlag;
    private String mPackageName = "com.test.mypackage";

    @Before
    public void setUp() {
        mContext = spy(ApplicationProvider.getApplicationContext());
        mResources = spy(mContext.getResources());
        mUtils = new UserAspectRatioManager(mContext);

        when(mContext.getResources()).thenReturn(mResources);

        mOriginalSettingsFlag = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS);
        setAspectRatioSettingsBuildTimeFlagEnabled(true);
        setAspectRatioSettingsDeviceConfigEnabled("true" /* enabled */, false /* makeDefault */);

        mOriginalFullscreenFlag = DeviceConfig.getProperty(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER, KEY_ENABLE_USER_ASPECT_RATIO_FULLSCREEN);
        setAspectRatioFullscreenBuildTimeFlagEnabled(true);
        setAspectRatioFullscreenDeviceConfigEnabled("true" /* enabled */, false /* makeDefault */);
    }

    @After
    public void tearDown() {
        setAspectRatioSettingsDeviceConfigEnabled(mOriginalSettingsFlag, true /* makeDefault */);
        setAspectRatioFullscreenDeviceConfigEnabled(mOriginalFullscreenFlag,
                true /* makeDefault */);
    }

    @Test
    public void testCanDisplayAspectRatioUi() {
        final ApplicationInfo canDisplay = new ApplicationInfo();
        canDisplay.packageName = "com.app.candisplay";
        addResolveInfoLauncherEntry(canDisplay.packageName);

        assertTrue(mUtils.canDisplayAspectRatioUi(canDisplay));

        final ApplicationInfo noLauncherEntry = new ApplicationInfo();
        noLauncherEntry.packageName = "com.app.nolauncherentry";

        assertFalse(mUtils.canDisplayAspectRatioUi(noLauncherEntry));
    }

    @Test
    public void testCanDisplayAspectRatioUi_hasLauncher_propertyFalse_returnFalse()
            throws PackageManager.NameNotFoundException {
        mockProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, false);

        final ApplicationInfo canDisplay = new ApplicationInfo();
        canDisplay.packageName = mPackageName;
        addResolveInfoLauncherEntry(canDisplay.packageName);

        assertFalse(mUtils.canDisplayAspectRatioUi(canDisplay));
    }

    @Test
    public void testCanDisplayAspectRatioUi_noLauncher_propertyTrue_returnFalse()
            throws PackageManager.NameNotFoundException {
        mockProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, true);

        final ApplicationInfo noLauncherEntry = new ApplicationInfo();
        noLauncherEntry.packageName = mPackageName;

        assertFalse(mUtils.canDisplayAspectRatioUi(noLauncherEntry));
    }

    @Test
    public void testIsFeatureEnabled() {
        assertTrue(UserAspectRatioManager.isFeatureEnabled(mContext));
    }

    @Test
    public void testIsFeatureEnabled_disabledBuildTimeFlag_returnFalse() {
        setAspectRatioSettingsBuildTimeFlagEnabled(false);
        assertFalse(UserAspectRatioManager.isFeatureEnabled(mContext));
    }

    @Test
    public void testIsFeatureEnabled_disabledRuntimeFlag_returnFalse() {
        setAspectRatioSettingsDeviceConfigEnabled("false" /* enabled */, false /* makeDefault */);
        assertFalse(UserAspectRatioManager.isFeatureEnabled(mContext));
    }

    @Test
    public void testIsFullscreenOptionEnabled() {
        assertTrue(mUtils.isFullscreenOptionEnabled(mPackageName));
    }

    @Test
    public void testIsFullscreenOptionEnabled_settingsDisabled_returnFalse() {
        setAspectRatioFullscreenBuildTimeFlagEnabled(false);
        assertFalse(mUtils.isFullscreenOptionEnabled(mPackageName));
    }

    @Test
    public void testIsFullscreenOptionEnabled_disabledBuildTimeFlag_returnFalse() {
        setAspectRatioFullscreenBuildTimeFlagEnabled(false);
        assertFalse(mUtils.isFullscreenOptionEnabled(mPackageName));
    }

    @Test
    public void testIsFullscreenOptionEnabled_disabledRuntimeFlag_returnFalse() {
        setAspectRatioFullscreenDeviceConfigEnabled("false" /* enabled */, false /*makeDefault */);
        assertFalse(mUtils.isFullscreenOptionEnabled(mPackageName));
    }

    @Test
    public void testIsFullscreenOptionEnabled_propertyFalse_returnsFalse()
            throws PackageManager.NameNotFoundException {
        mockProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE, false);
        assertFalse(mUtils.isFullscreenOptionEnabled(mPackageName));
    }

    @Test
    public void testIsFullscreenOptionEnabled_propertyTrue_configDisabled_returnsFalse()
            throws PackageManager.NameNotFoundException {
        mockProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE, true);
        setAspectRatioFullscreenDeviceConfigEnabled("false" /* enabled */, false /*makeDefault */);

        assertFalse(mUtils.isFullscreenOptionEnabled(mPackageName));
    }

    @Test
    public void testHasAspectRatioOption_fullscreen() {
        assertTrue(mUtils.hasAspectRatioOption(USER_MIN_ASPECT_RATIO_FULLSCREEN,
                mPackageName));
        assertTrue(mUtils.hasAspectRatioOption(USER_MIN_ASPECT_RATIO_SPLIT_SCREEN,
                mPackageName));

        // Only fullscreen option should be disabled
        when(mUtils.isFullscreenOptionEnabled(mPackageName)).thenReturn(false);
        assertFalse(mUtils.hasAspectRatioOption(USER_MIN_ASPECT_RATIO_FULLSCREEN,
                mPackageName));
        assertTrue(mUtils.hasAspectRatioOption(USER_MIN_ASPECT_RATIO_SPLIT_SCREEN,
                mPackageName));
    }

    @Test
    public void testGetUserMinAspectRatioEntry() {
        // R.string.user_aspect_ratio_app_default
        final String appDefault = ResourcesUtils.getResourcesString(mContext,
                "user_aspect_ratio_app_default");
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_UNSET, mPackageName))
                .isEqualTo(appDefault);
        // should always return default if value does not correspond to anything
        assertThat(mUtils.getUserMinAspectRatioEntry(-1, mPackageName))
                .isEqualTo(appDefault);
        // R.string.user_aspect_ratio_half_screen
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_SPLIT_SCREEN,
                mPackageName)).isEqualTo(ResourcesUtils.getResourcesString(mContext,
                        "user_aspect_ratio_half_screen"));
        // R.string.user_aspect_ratio_3_2
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_3_2, mPackageName))
                .isEqualTo(ResourcesUtils.getResourcesString(mContext, "user_aspect_ratio_3_2"));
        // R,string.user_aspect_ratio_4_3
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_4_3, mPackageName))
                .isEqualTo(ResourcesUtils.getResourcesString(mContext, "user_aspect_ratio_4_3"));
        // R.string.user_aspect_ratio_16_9
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_16_9, mPackageName))
                .isEqualTo(ResourcesUtils.getResourcesString(mContext, "user_aspect_ratio_16_9"));
        // R.string.user_aspect_ratio_fullscreen
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_FULLSCREEN,
                mPackageName)).isEqualTo(ResourcesUtils.getResourcesString(mContext,
                        "user_aspect_ratio_fullscreen"));
    }

    @Test
    public void testGetUserMinAspectRatioEntry_fullscreenDisabled_shouldReturnDefault() {
        setAspectRatioFullscreenBuildTimeFlagEnabled(false);
        assertThat(mUtils.getUserMinAspectRatioEntry(USER_MIN_ASPECT_RATIO_FULLSCREEN,
                mPackageName)).isEqualTo(ResourcesUtils.getResourcesString(mContext,
                        "user_aspect_ratio_app_default"));
    }

    private void mockProperty(String propertyName, boolean value)
            throws PackageManager.NameNotFoundException {
        PackageManager.Property prop = new PackageManager.Property(
                propertyName, value, mPackageName, "" /* className */);
        PackageManager pm = mock(PackageManager.class);
        when(mContext.getPackageManager()).thenReturn(pm);
        when(pm.getProperty(propertyName, mPackageName)).thenReturn(prop);
    }

    private void setAspectRatioSettingsBuildTimeFlagEnabled(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_appCompatUserAppAspectRatioSettingsIsEnabled))
                .thenReturn(enabled);
    }

    private void setAspectRatioSettingsDeviceConfigEnabled(String enabled, boolean makeDefault) {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_ENABLE_USER_ASPECT_RATIO_SETTINGS, enabled, makeDefault);
    }

    private void setAspectRatioFullscreenBuildTimeFlagEnabled(boolean enabled) {
        when(mResources.getBoolean(R.bool.config_appCompatUserAppAspectRatioFullscreenIsEnabled))
                .thenReturn(enabled);
    }

    private void setAspectRatioFullscreenDeviceConfigEnabled(String enabled, boolean makeDefault) {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_ENABLE_USER_ASPECT_RATIO_FULLSCREEN, enabled, makeDefault);
    }

    private void addResolveInfoLauncherEntry(String packageName) {
        final ResolveInfo resolveInfo = mock(ResolveInfo.class);
        final ActivityInfo activityInfo = mock(ActivityInfo.class);
        activityInfo.packageName = packageName;
        resolveInfo.activityInfo = activityInfo;
        mUtils.addInfoHasLauncherEntry(resolveInfo);
    }
}
