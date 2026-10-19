package com.cloudmusictool.app;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块进程侧的 XposedService 绑定。
 * 开关页必须通过 {@link XposedService#getRemotePreferences} 写入，
 * 因为网易云音乐进程读的是那份存储——写进模块自己的 MODE_PRIVATE 对目标进程不可见。
 */
public final class ServiceBridge {

    private static final String TAG = "CloudMusicTool";
    private static final String AUTHORITY_SUFFIX = ".XposedService";

    private static final AtomicReference<XposedService> sService = new AtomicReference<>();

    private static boolean sListenerRegistered;

    /** service 真正绑定后要跑的一次性重绘；由 UI 层设置。 */
    private static Runnable pendingRender = () -> { };

    private ServiceBridge() {
    }

    /** UI 层在等 service 绑定时挂一个重绘回调。 */
    public static void setOnBound(Runnable r) {
        pendingRender = (r == null) ? () -> { } : r;
    }

    public static synchronized void bind(Context context) {
        if (sListenerRegistered) {
            return;
        }
        sListenerRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                if (safeApi(service) < 101) {
                    Log.i(TAG, "ignoring placeholder binder, api=" + safeApi(service));
                    return;
                }
                XposedService previous = sService.getAndSet(service);
                Log.i(TAG, "service bound, api=" + safeApi(service) + " replaced=" + (previous != null));
                pendingRender.run();
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (sService.compareAndSet(service, null)) {
                    Log.w(TAG, "service died");
                }
            }
        });
        if (sService.get() == null) {
            poke(context);
        }
    }

    public static synchronized XposedService get() {
        return sService.get();
    }

    public static android.content.SharedPreferences remotePreferences(String group) {
        XposedService service = sService.get();
        if (service == null) {
            Log.w(TAG, "remote prefs requested before service bound");
            return null;
        }
        try {
            if ((service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0) {
                Log.w(TAG, "framework lacks PROP_CAP_REMOTE");
                return null;
            }
            return service.getRemotePreferences(group);
        } catch (Throwable t) {
            Log.w(TAG, "remote prefs unavailable", t);
            return null;
        }
    }

    public static void poke(Context context) {
        try {
            android.os.Bundle extras = new android.os.Bundle();
            extras.putBinder("binder", new android.os.Binder());
            context.getContentResolver().call(
                    android.net.Uri.parse("content://" + context.getPackageName() + AUTHORITY_SUFFIX),
                    "SendBinder", null, extras);
        } catch (Throwable t) {
            Log.w(TAG, "poke failed", t);
        }
    }

    private static int safeApi(XposedService service) {
        try {
            return service.getApiVersion();
        } catch (Throwable t) {
            return 0;
        }
    }
}
