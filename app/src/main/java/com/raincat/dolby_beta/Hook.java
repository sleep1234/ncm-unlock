package com.raincat.dolby_beta;

import android.content.Context;

import com.raincat.dolby_beta.hook.BlackHook;
import com.raincat.dolby_beta.hook.DownloadMD5Hook;
import com.raincat.dolby_beta.hook.EAPIHook;
import com.raincat.dolby_beta.hook.GrayHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * NCM-Unlock: 主进程 Hook 入口（精简版）
 * 仅初始化 VIP 歌曲解锁相关的 Hook
 */
public class Hook {
    private static final String PACKAGE_NAME = "com.netease.cloudmusic";

    public Hook(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("com.netease.cloudmusic.NeteaseMusicApplication", lpparam.classLoader),
                "attachBaseContext", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        final Context context = (Context) param.thisObject;
                        final int versionCode = context.getPackageManager()
                                .getPackageInfo(PACKAGE_NAME, 0).versionCode;

                        final String processName = context.getApplicationInfo().processName;
                        if (!PACKAGE_NAME.equals(processName)) {
                            XposedBridge.log("[ncm-unlock] skip non-main process: " + processName);
                            return;
                        }

                        XposedBridge.log("[ncm-unlock] initializing in main process, versionCode=" + versionCode);

                        // 黑胶 VIP 伪装
                        new BlackHook(context, versionCode);
                        // 不变灰
                        new GrayHook(context);
                        // EAPI 拦截（VIP 歌曲 URL 替换）
                        new EAPIHook(context, versionCode, context.getClassLoader());
                        // 下载 MD5 校验绕过
                        new DownloadMD5Hook(context);

                        XposedBridge.log("[ncm-unlock] all hooks initialized");
                    }
                });
    }
}
