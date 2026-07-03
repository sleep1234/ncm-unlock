package com.raincat.dolby_beta;

import android.text.TextUtils;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * NCM-Unlock: 网易云音乐 VIP 歌曲解锁独立模块
 * 从 dolby_beta 剥离，仅保留核心解锁功能
 */
public class MainHook implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Exception {
        if (!TextUtils.isEmpty(lpparam.packageName)
                && lpparam.packageName.equals("com.netease.cloudmusic")) {
            new Hook(lpparam);
        }
    }
}
