package com.raincat.dolby_beta.helper;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XposedBridge;

/**
 * NCM-Unlock: 设置管理
 * 存储用户音质偏好等配置
 */
public class SettingsHelper {
    private static final String PREFS_NAME = "ncm_unlock_settings";
    private static final String KEY_AUDIO_QUALITY = "audio_quality";
    private static final String KEY_ENABLE_TOAST = "enable_toast";

    // 音质选项
    public static final int QUALITY_STANDARD = 128;   // 标准
    public static final int QUALITY_HIGHER = 192;      // 较高
    public static final int QUALITY_EXHIGH = 320;      // 极高
    public static final int QUALITY_LOSSLESS = 999;    // 无损（最高可用）

    private static SharedPreferences prefs;

    public static void init(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        XposedBridge.log("[ncm-unlock] Settings initialized, quality=" + getAudioQuality());
    }

    /** 获取音质设置，默认无损(999) */
    public static int getAudioQuality() {
        return prefs != null ? prefs.getInt(KEY_AUDIO_QUALITY, QUALITY_LOSSLESS) : QUALITY_LOSSLESS;
    }

    /** 设置音质 */
    public static void setAudioQuality(int quality) {
        if (prefs != null) {
            prefs.edit().putInt(KEY_AUDIO_QUALITY, quality).apply();
            XposedBridge.log("[ncm-unlock] Quality set to " + quality);
        }
    }

    /** 是否显示 Toast */
    public static boolean isToastEnabled() {
        return prefs != null ? prefs.getBoolean(KEY_ENABLE_TOAST, true) : true;
    }

    /** 设置 Toast 开关 */
    public static void setToastEnabled(boolean enabled) {
        if (prefs != null) {
            prefs.edit().putBoolean(KEY_ENABLE_TOAST, enabled).apply();
        }
    }

    /** 获取音质名称 */
    public static String getQualityName(int quality) {
        switch (quality) {
            case QUALITY_STANDARD: return "标准 128kbps";
            case QUALITY_HIGHER: return "较高 192kbps";
            case QUALITY_EXHIGH: return "极高 320kbps";
            case QUALITY_LOSSLESS: return "无损 (FLAC)";
            default: return "无损 (FLAC)";
        }
    }
}
