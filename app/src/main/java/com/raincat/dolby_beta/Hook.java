package com.raincat.dolby_beta;

import android.content.Context;

import com.raincat.dolby_beta.helper.SettingsHelper;
import com.raincat.dolby_beta.hook.BlackHook;
import com.raincat.dolby_beta.hook.DownloadMD5Hook;
import com.raincat.dolby_beta.hook.EAPIHook;
import com.raincat.dolby_beta.hook.GrayHook;
import com.raincat.dolby_beta.hook.SettingHook;

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

                        try {
                            // 初始化设置
                            SettingsHelper.init(context);
                            XposedBridge.log("[ncm-unlock] SettingsHelper OK");
                        } catch (Throwable t) {
                            XposedBridge.log("[ncm-unlock] SettingsHelper FAILED: " + t.getMessage());
                        }

                        try {
                            // 设置页面 Hook
                            new SettingHook(context, versionCode);
                            XposedBridge.log("[ncm-unlock] SettingHook OK");
                        } catch (Throwable t) {
                            XposedBridge.log("[ncm-unlock] SettingHook FAILED: " + t.getMessage());
                        }

                        try {
                            // 黑胶 VIP 伪装
                            new BlackHook(context, versionCode);
                            XposedBridge.log("[ncm-unlock] BlackHook OK");
                        } catch (Throwable t) {
                            XposedBridge.log("[ncm-unlock] BlackHook FAILED: " + t.getMessage());
                        }

                        try {
                            // 不变灰
                            new GrayHook(context);
                            XposedBridge.log("[ncm-unlock] GrayHook OK");
                        } catch (Throwable t) {
                            XposedBridge.log("[ncm-unlock] GrayHook FAILED: " + t.getMessage());
                        }

                        try {
                            // EAPI 拦截（VIP 歌曲 URL 替换）
                            new EAPIHook(context, versionCode, context.getClassLoader());
                            XposedBridge.log("[ncm-unlock] EAPIHook OK");
                        } catch (Throwable t) {
                            XposedBridge.log("[ncm-unlock] EAPIHook FAILED: " + t.getMessage());
                        }

                        try {
                            // 下载 MD5 校验绕过
                            new DownloadMD5Hook(context);
                            XposedBridge.log("[ncm-unlock] DownloadMD5Hook OK");
                        } catch (Throwable t) {
                            XposedBridge.log("[ncm-unlock] DownloadMD5Hook FAILED: " + t.getMessage());
                        }

                        XposedBridge.log("[ncm-unlock] all hooks initialized");
                    }
                });
    }
}
