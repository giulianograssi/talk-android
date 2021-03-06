/*
 *
 *   Nextcloud Talk application
 *
 *   @author Mario Danic
 *   Copyright (C) 2017 Mario Danic (mario@lovelyhq.com)
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.nextcloud.talk.application;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.arch.lifecycle.ProcessLifecycleOwner;
import android.content.Context;
import android.os.Build;
import android.support.multidex.MultiDex;
import android.support.multidex.MultiDexApplication;
import android.util.Log;

import com.nextcloud.talk.dagger.modules.BusModule;
import com.nextcloud.talk.dagger.modules.ContextModule;
import com.nextcloud.talk.dagger.modules.DatabaseModule;
import com.nextcloud.talk.dagger.modules.RestModule;
import com.nextcloud.talk.jobs.AccountRemovalWorker;
import com.nextcloud.talk.jobs.CapabilitiesWorker;
import com.nextcloud.talk.jobs.PushRegistrationWorker;
import com.nextcloud.talk.utils.ClosedInterfaceImpl;
import com.nextcloud.talk.utils.DeviceUtils;
import com.nextcloud.talk.utils.DisplayUtils;
import com.nextcloud.talk.utils.database.user.UserModule;
import com.nextcloud.talk.utils.singletons.ApplicationWideStateHolder;
import com.nextcloud.talk.webrtc.MagicWebRTCUtils;
import com.squareup.leakcanary.LeakCanary;
import com.squareup.leakcanary.RefWatcher;

import org.webrtc.PeerConnectionFactory;
import org.webrtc.voiceengine.WebRtcAudioManager;
import org.webrtc.voiceengine.WebRtcAudioUtils;

import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import androidx.work.Configuration;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import autodagger.AutoComponent;
import autodagger.AutoInjector;

@AutoComponent(
        modules = {
                BusModule.class,
                ContextModule.class,
                DatabaseModule.class,
                RestModule.class,
                UserModule.class,
        }
)

@Singleton
@AutoInjector(NextcloudTalkApplication.class)
public class NextcloudTalkApplication extends MultiDexApplication implements LifecycleObserver {
    private static final String TAG = NextcloudTalkApplication.class.getSimpleName();

    //region Public variables
    public static RefWatcher refWatcher;
    //endregion

    //region Singleton
    protected static NextcloudTalkApplication sharedApplication;
    //region Fields (components)
    protected NextcloudTalkApplicationComponent componentApplication;
    //endregion

    public static NextcloudTalkApplication getSharedApplication() {
        return sharedApplication;
    }
    //endregion

    //region private methods
    private void initializeWebRtc() {
        try {
            if (MagicWebRTCUtils.HARDWARE_AEC_BLACKLIST.contains(Build.MODEL)) {
                WebRtcAudioUtils.setWebRtcBasedAcousticEchoCanceler(true);
            }

            if (!MagicWebRTCUtils.OPEN_SL_ES_WHITELIST.contains(Build.MODEL)) {
                WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);
            }

            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this)
                    .setEnableVideoHwAcceleration(MagicWebRTCUtils.shouldEnableVideoHardwareAcceleration())
                    .createInitializationOptions());
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, e);
        }
    }

    //endregion

    //region Overridden methods
    @Override
    public void onCreate() {
        super.onCreate();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);

        sharedApplication = this;

        initializeWebRtc();
        DisplayUtils.useCompatVectorIfNeeded();
        buildComponent();

        componentApplication.inject(this);
        refWatcher = LeakCanary.install(this);

        new ClosedInterfaceImpl().providerInstallerInstallIfNeededAsync();
        DeviceUtils.ignoreSpecialBatteryFeatures();

        OneTimeWorkRequest pushRegistrationWork = new OneTimeWorkRequest.Builder(PushRegistrationWorker.class).build();
        OneTimeWorkRequest accountRemovalWork = new OneTimeWorkRequest.Builder(AccountRemovalWorker.class).build();
        PeriodicWorkRequest periodicCapabilitiesUpdateWork = new PeriodicWorkRequest.Builder(CapabilitiesWorker.class,
                1, TimeUnit.DAYS).build();

        WorkManager.initialize(getApplicationContext(), new Configuration.Builder().build());
        WorkManager.getInstance().enqueue(pushRegistrationWork);
        WorkManager.getInstance().enqueue(accountRemovalWork);
        WorkManager.getInstance().enqueueUniquePeriodicWork("DailyCapabilitiesUpdateWork",
                ExistingPeriodicWorkPolicy.REPLACE, periodicCapabilitiesUpdateWork);

    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        sharedApplication = null;
    }

    //endregion

    //region Getters
    public NextcloudTalkApplicationComponent getComponentApplication() {
        return componentApplication;
    }
    //endregion

    //region Protected methods
    protected void buildComponent() {
        componentApplication = DaggerNextcloudTalkApplicationComponent.builder()
                .busModule(new BusModule())
                .contextModule(new ContextModule(getApplicationContext()))
                .databaseModule(new DatabaseModule())
                .restModule(new RestModule())
                .userModule(new UserModule())
                .build();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
    //endregion

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    private void onAppBackgrounded() {
        ApplicationWideStateHolder.getInstance().setInForeground(false);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    private void onAppForegrounded() {
        ApplicationWideStateHolder.getInstance().setInForeground(true);
    }
}
