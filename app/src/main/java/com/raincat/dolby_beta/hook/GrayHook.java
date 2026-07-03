package com.raincat.dolby_beta.hook;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;

/**
 * NCM-Unlock: 不变灰 + 解除音质限制
 */
public class GrayHook {
    public GrayHook(Context context) {
        // 强制显示所有歌曲（不变灰）
        findAndHookMethod(findClass("com.netease.cloudmusic.meta.MusicInfo", context.getClassLoader()),
                "hasCopyRight", XC_MethodReplacement.returnConstant(true));

        // 解除 SongPrivilege 的音质限制
        Class<?> songPrivilegeClass = XposedHelpers.findClassIfExists(
                "com.netease.cloudmusic.meta.virtual.SongPrivilege", context.getClassLoader());
        if (songPrivilegeClass != null) {
            Method method = null;
            try {
                method = songPrivilegeClass.getMethod("setDownloadMaxbr", int.class);
            } catch (NoSuchMethodException e) {
                try {
                    method = songPrivilegeClass.getMethod("setFreeLevel", int.class);
                } catch (NoSuchMethodException ignored) {}
            }
            if (method != null) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object object = param.thisObject;
                        long id = (long) XposedHelpers.callMethod(object, "getId");
                        if (id == 0) return;

                        try {
                            Field[] fields = object.getClass().getDeclaredFields();
                            int maxbr = 0;
                            for (Field field : fields) {
                                if (field.getType() == int.class && "maxbr".equals(field.getName())) {
                                    field.setAccessible(true);
                                    maxbr = (int) field.get(object);
                                    break;
                                }
                            }
                            if (maxbr == 0) maxbr = 999000;

                            param.args[0] = maxbr;
                            XposedHelpers.callMethod(object, "setSubPriv", 1);
                            XposedHelpers.callMethod(object, "setSharePriv", 1);
                            XposedHelpers.callMethod(object, "setCommentPriv", 1);
                            XposedHelpers.callMethod(object, "setDownMaxLevel", maxbr);
                            XposedHelpers.callMethod(object, "setPlayMaxLevel", maxbr);
                            try {
                                XposedHelpers.callMethod(object, "setPlayMaxbr", maxbr);
                            } catch (NoSuchMethodError ignored) {}
                        } catch (Exception ignored) {}
                    }
                });
            }
        }

        XposedBridge.log("[ncm-unlock] GrayHook initialized");
    }
}
