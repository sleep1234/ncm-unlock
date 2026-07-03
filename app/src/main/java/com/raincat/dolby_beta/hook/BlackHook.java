package com.raincat.dolby_beta.hook;

import android.content.Context;

import org.json.JSONObject;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

/**
 * NCM-Unlock: 黑胶 VIP 伪装
 * 伪造 vipType=100, 音乐包=220，有效期+1年
 */
public class BlackHook {
    public BlackHook(Context context, int versionCode) {
        if (versionCode < 138) {
            // 旧版本：直接修改 Profile 对象
            XposedBridge.hookAllMethods(
                    findClass("com.netease.cloudmusic.meta.Profile", context.getClassLoader()),
                    "setUserPoint", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            XposedHelpers.callMethod(param.thisObject, "setVipType", 100);
                            XposedHelpers.callMethod(param.thisObject, "setVipProExpireTime", System.currentTimeMillis() + 31536000000L);
                            XposedHelpers.callMethod(param.thisObject, "setExpireTime", System.currentTimeMillis() + 31536000000L);
                        }
                    });

            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "i", XC_MethodReplacement.returnConstant(0));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "j", XC_MethodReplacement.returnConstant("免费"));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "o", XC_MethodReplacement.returnConstant(false));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "s", XC_MethodReplacement.returnConstant(false));
        } else {
            // 新版本：修改 UserPrivilege JSON
            findAndHookMethod(
                    findClass("com.netease.cloudmusic.meta.virtual.UserPrivilege", context.getClassLoader()),
                    "fromJson", JSONObject.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            JSONObject object = (JSONObject) param.args[0];
                            if (object.optInt("code") == 200 && !object.isNull("data")) {
                                try {
                                    JSONObject data = object.getJSONObject("data");
                                    // 修改会员信息
                                    if (!data.isNull("associator")) {
                                        JSONObject assoc = data.getJSONObject("associator");
                                        assoc.put("expireTime", System.currentTimeMillis() + 31536000000L);
                                        assoc.put("vipCode", 100);
                                    }
                                    if (!data.isNull("musicPackage")) {
                                        JSONObject pkg = data.getJSONObject("musicPackage");
                                        pkg.put("expireTime", System.currentTimeMillis() + 31536000000L);
                                        pkg.put("vipCode", 220);
                                    }
                                    data.put("redVipAnnualCount", 1);
                                    data.put("redVipLevel", 9);
                                    param.args[0] = object;
                                } catch (Exception e) {
                                    XposedBridge.log("[ncm-unlock] BlackHook error: " + e.getMessage());
                                }
                            }
                        }
                    });

            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "getPoints", XC_MethodReplacement.returnConstant(0));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "getPrice", XC_MethodReplacement.returnConstant("免费"));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "isVip", XC_MethodReplacement.returnConstant(false));
            findAndHookMethod(findClass("com.netease.cloudmusic.theme.core.ThemeInfo", context.getClassLoader()),
                    "isDigitalAlbum", XC_MethodReplacement.returnConstant(false));
        }

        // 音质切换：解除限制
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "isVipFee", XC_MethodReplacement.returnConstant(false));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getPlayMaxLevel", XC_MethodReplacement.returnConstant(999000));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getDownMaxLevel", XC_MethodReplacement.returnConstant(999000));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getFee", XC_MethodReplacement.returnConstant(0));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getPayed", XC_MethodReplacement.returnConstant(0));
        XposedBridge.hookAllMethods(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "isFee", XC_MethodReplacement.returnConstant(false));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.SongPrivilege", context.getClassLoader()),
                "canShare", XC_MethodReplacement.returnConstant(true));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.SongPrivilege", context.getClassLoader()),
                "getFreeLevel", XC_MethodReplacement.returnConstant(999000));
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.virtual.ResourcePrivilege", context.getClassLoader()),
                "getFlag", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(((int) param.getResult() & 0x8) == 0 ? 0 : param.getResult());
                    }
                });

        XposedBridge.log("[ncm-unlock] BlackHook initialized");
    }
}
