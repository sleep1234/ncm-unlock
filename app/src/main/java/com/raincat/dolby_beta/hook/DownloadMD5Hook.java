package com.raincat.dolby_beta.hook;

import android.content.Context;

import de.robv.android.xposed.XposedBridge;

/**
 * NCM-Unlock: 下载 MD5 校验绕过（简化版）
 * 独立模块暂不实现，需要 ClassHelper 支持
 */
public class DownloadMD5Hook {
    public DownloadMD5Hook(Context context) {
        XposedBridge.log("[ncm-unlock] DownloadMD5Hook: skipped (not implemented in standalone module)");
    }
}
