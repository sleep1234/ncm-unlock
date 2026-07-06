package com.raincat.dolby_beta.helper;

import com.google.gson.Gson;
import com.raincat.dolby_beta.model.NeteaseSongListBean;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import de.robv.android.xposed.XposedBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * NCM-Unlock: VIP 歌曲 URL 替换核心
 * 通过 GD Studio API 获取完整播放 URL，替换试听片段
 */
public class EAPIHelper {
    private static final Gson gson = new Gson();
    private static final String GD_API_BASE = "https://music-api.gdstudio.xyz/api.php";
    private static final long CACHE_TTL_MS = 2 * 60 * 1000; // 2 分钟缓存

    /** 由 EAPIHook 初始化时设置，用于 Toast 提示 */
    private static Context appContext;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void setContext(Context ctx) {
        appContext = ctx; // 直接使用传入的 context
        debugLog("setContext: ctx=" + (ctx != null ? "ok" : "null"));
    }

    private static void showToast(String msg) {
        if (!SettingsHelper.isToastEnabled()) return;
        debugLog("showToast: " + msg + " ctx=" + (appContext != null ? "ok" : "null"));
        if (appContext == null) return;
        mainHandler.post(() -> {
            try {
                Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                debugLog("showToast error: " + e.getMessage());
            }
        });
    }

    private static void debugLog(String msg) {
        XposedBridge.log("[ncm-unlock] " + msg);
    }

    /** GD API 返回的完整结果 */
    public static class GDResult {
        public final String url;
        public final int br;
        public final int size;
        GDResult(String url, int br, int size) { this.url = url; this.br = br; this.size = size; }
    }

    /** GD API URL 缓存 */
    private static final HashMap<Long, CachedUrl> gdUrlCache = new HashMap<>();

    private static class CachedUrl {
        final String url;
        final int br;
        final int size;
        final long timestamp;
        CachedUrl(String url, int br, int size) {
            this.url = url; this.br = br; this.size = size;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() { return System.currentTimeMillis() - timestamp > CACHE_TTL_MS; }
    }

    /**
     * 通过 GD Studio API 获取歌曲完整播放 URL + 元数据（带缓存 + 3次重试）
     */
    public static GDResult fetchGDResult(long songId, int br) {
        synchronized (gdUrlCache) {
            CachedUrl cached = gdUrlCache.get(songId);
            if (cached != null && !cached.isExpired()) {
                debugLog("GD CACHE HIT song=" + songId + " br=" + cached.br + " size=" + cached.size);
                return new GDResult(cached.url, cached.br, cached.size);
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (attempt > 0) {
                    debugLog("GD API retry #" + attempt + " song=" + songId);
                    Thread.sleep(1000);
                }
                String apiUrl = GD_API_BASE + "?types=url&source=netease&id=" + songId + "&br=" + br;
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");

                int code = conn.getResponseCode();
                if (code == 503 && attempt < 2) { conn.disconnect(); continue; }
                if (code != 200) {
                    debugLog("GD API HTTP " + code + " song=" + songId);
                    conn.disconnect();
                    if (attempt < 2) continue;
                    return null;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                String json = sb.toString();
                if (json.contains("\"url\"") && json.contains("\"http")) {
                    org.json.JSONObject obj = new org.json.JSONObject(json);
                    String fetchedUrl = obj.getString("url");
                    int fetchedBr = obj.optInt("br", 0);
                    int fetchedSize = obj.optInt("size", 0);
                    if (fetchedUrl != null && fetchedUrl.startsWith("http")) {
                        debugLog("GD API OK song=" + songId + " br=" + fetchedBr + " size=" + fetchedSize + " url=" + fetchedUrl);
                        synchronized (gdUrlCache) {
                            gdUrlCache.put(songId, new CachedUrl(fetchedUrl, fetchedBr, fetchedSize));
                        }
                        return new GDResult(fetchedUrl, fetchedBr, fetchedSize);
                    }
                }
                debugLog("GD API no valid URL for song " + songId);
                if (attempt < 2) continue;
                return null;
            } catch (Exception e) {
                debugLog("GD API error song=" + songId + " attempt=" + (attempt+1) + ": " + e.getMessage());
                if (attempt >= 2) return null;
            }
        }
        return null;
    }

    /** 兼容旧接口 */
    public static String fetchUrlFromGD(long songId, int br) {
        GDResult result = fetchGDResult(songId, br);
        return result != null ? result.url : null;
    }

    /**
     * 修改 player/url 响应：清除限制字段 + 用 GD API 替换 VIP 歌曲 URL
     */
    public static String modifyPlayer(String original) {
        debugLog("modifyPlayer input len=" + original.length());
        NeteaseSongListBean listBean = gson.fromJson(original, NeteaseSongListBean.class);
        if (listBean.getData() == null || listBean.getData().isEmpty()) {
            debugLog("modifyPlayer: no data, returning original");
            return original;
        }

        NeteaseSongListBean out = new NeteaseSongListBean();
        out.setCode(200);
        out.setData(new ArrayList<>());

        int vipCount = 0;
        int successCount = 0;

        for (NeteaseSongListBean.DataBean bean : listBean.getData()) {
            if ((bean.getFlag() & 0x8) != 0) { // 云盘歌曲不处理
                out.getData().add(bean);
                continue;
            }

            int originalFee = bean.getFee();
            boolean hadFreeTrial = bean.getFreeTrialInfo() != null;
            boolean isVip = originalFee > 0;

            // 清除限制
            bean.setFee(0);
            bean.setFlag(0);
            bean.setPayed(0);
            bean.setFreeTrialInfo(null);

            String url = bean.getUrl();
            if (url != null) {
                // 去掉 query string
                if (url.contains("?")) {
                    bean.setUrl(url.substring(0, url.indexOf("?")));
                }
                // VIP 歌曲 → GD API 替换
                if (hadFreeTrial || isVip) {
                    vipCount++;
                    // 使用用户设置的音质，获取不到时用 999 兜底
                    int quality = SettingsHelper.getAudioQuality();
                    GDResult gd = fetchGDResult(bean.getId(), quality);
                    if (gd == null && quality != SettingsHelper.QUALITY_LOSSLESS) {
                        debugLog("song " + bean.getId() + " quality=" + quality + " failed, fallback to 999");
                        gd = fetchGDResult(bean.getId(), SettingsHelper.QUALITY_LOSSLESS);
                    }
                    if (gd != null) {
                        bean.setUrl(gd.url);
                        bean.setBr(gd.br);
                        bean.setSize(gd.size);
                        if (gd.url.contains(".flac")) { bean.setType("flac"); bean.setEncodeType("flac"); }
                        else if (gd.url.contains(".mp3")) { bean.setType("mp3"); bean.setEncodeType("mp3"); }
                        debugLog("song " + bean.getId() + " replaced => " + gd.url);
                        successCount++;
                    } else {
                        debugLog("song " + bean.getId() + " GD failed, keeping original");
                    }
                }
            }
            out.getData().add(bean);
        }

        // 每次 player/url 请求只弹一次 Toast
        if (vipCount > 0) {
            if (successCount == vipCount) {
                showToast("✅ 替换成功 " + successCount + " 首 VIP 歌曲");
            } else {
                showToast("⚠️ 替换 " + successCount + "/" + vipCount + " 首");
            }
        }

        String result = gson.toJson(out);
        debugLog("modifyPlayer result len=" + result.length());
        return result;
    }
}
