package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.util.Log;

import com.raincat.dolby_beta.helper.EAPIHelper;
import com.raincat.dolby_beta.utils.NeteaseAES2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * EAPI Hook — v91: Full privilege modification with enhanced debug + ResponseBody fix
 *
 * v90 findings:
 * - privilege hook successfully modifies 28 songs but "REPLACED OK" never prints
 * - rebuildResponse may silently fail at ResponseBody.create() or newBuilder().body()
 * - location/info body only has 47 bytes (geo check, not song data)
 *
 * v91 approach:
 * 1. Add detailed step-by-step debug logs in rebuildResponse
 * 2. Add step-by-step logs in afterHookedMethod around setResult
 * 3. Use try-catch around each individual step in rebuildResponse
 * 4. Same privilege modification as v90: fee=0, flag=0, payed=1, pl=320000, etc.
 * 5. Also intercept /eapi/song/enhance/location/info for play URL data
 */
public class EAPIHook {
    private static final String DEBUG_LOG_PATH = "/data/local/tmp/dolby_debug.log";
    private static final SimpleDateFormat SDF = new SimpleDateFormat("HH:mm:ss.SSS");

    private ClassLoader appClassLoader;

    private static void debugLog(String msg) {
        String fullMsg = "[ncm-unlock] " + msg;
        XposedBridge.log(fullMsg);
        Log.d("ncm-unlock", msg);
        try {
            FileWriter fw = new FileWriter(DEBUG_LOG_PATH, true);
            fw.write(SDF.format(new Date()) + " " + msg + "\n");
            fw.close();
        } catch (IOException ignored) {}
    }

    private static String getUrlFromRequest(Object request) {
        if (request == null) return null;
        try {
            Object url = XposedHelpers.callMethod(request, "url");
            return XposedHelpers.callMethod(url, "toString").toString();
        } catch (Exception e1) {
            try { return request.toString(); } catch (Exception e2) { return null; }
        }
    }

    private static String getUrlFromCall(Object call) {
        if (call == null) return null;
        try {
            Object request = XposedHelpers.callMethod(call, "request");
            return getUrlFromRequest(request);
        } catch (Exception e) { return null; }
    }

    /** Rebuild an OkHttp Response with a new body string — v91 enhanced debug */
    private static Object rebuildResponse(Object originalResponse, String newBodyString, ClassLoader cl) {
        try {
            debugLog("[V91-RB] step1: start rebuild, bodyLen=" + newBodyString.length());

            Class<?> mediaTypeClass = findClassIfExists("okhttp3.MediaType", cl);
            Class<?> responseBodyClass = findClassIfExists("okhttp3.ResponseBody", cl);

            if (responseBodyClass == null) {
                debugLog("[V91-RB] FAILED: ResponseBody class not found!");
                return null;
            }
            debugLog("[V91-RB] step2: found ResponseBody class: " + responseBodyClass.getName());

            Object newBody = null;

            // Try create(mediaType, string) first
            if (mediaTypeClass != null) {
                debugLog("[V91-RB] step3: trying MediaType.parse...");
                try {
                    Object mediaType = XposedHelpers.callStaticMethod(mediaTypeClass, "parse", "application/json;charset=utf-8");
                    debugLog("[V91-RB] step3a: mediaType=" + mediaType);
                    if (mediaType != null) {
                        // Try all method signatures
                        Method[] createMethods = responseBodyClass.getDeclaredMethods();
                        for (Method cm : createMethods) {
                            if (cm.getName().equals("create") && java.lang.reflect.Modifier.isStatic(cm.getModifiers())) {
                                Class<?>[] ptypes = cm.getParameterTypes();
                                debugLog("[V91-RB] step3b: create method: " + cm + " params=" + java.util.Arrays.toString(ptypes));
                            }
                        }
                        try {
                            newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", mediaType, newBodyString);
                            debugLog("[V91-RB] step3c: create(mediaType, string) succeeded");
                        } catch (Exception e1) {
                            debugLog("[V91-RB] step3c-fail: create(mediaType, string) error: " + e1.getMessage());
                            try {
                                newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", newBodyString, mediaType);
                                debugLog("[V91-RB] step3d: create(string, mediaType) succeeded");
                            } catch (Exception e2) {
                                debugLog("[V91-RB] step3d-fail: create(string, mediaType) error: " + e2.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    debugLog("[V91-RB] step3-fail: MediaType.parse error: " + e.getMessage());
                }
            } else {
                debugLog("[V91-RB] step3-skip: MediaType class not found");
            }

            if (newBody == null) {
                debugLog("[V91-RB] step4: trying create(string) without mediaType...");
                try {
                    newBody = XposedHelpers.callStaticMethod(responseBodyClass, "create", newBodyString);
                    debugLog("[V91-RB] step4: create(string) succeeded");
                } catch (Exception e) {
                    debugLog("[V91-RB] step4-fail: create(string) error: " + e.getMessage());
                    return null;
                }
            }

            debugLog("[V91-RB] step5: calling newBuilder()...");
            Object builder = XposedHelpers.callMethod(originalResponse, "newBuilder");
            debugLog("[V91-RB] step5a: newBuilder OK, calling body(newBody)...");
            XposedHelpers.callMethod(builder, "body", newBody);
            debugLog("[V91-RB] step5b: body(newBody) OK");

            try {
                // Try to clean up Content-Length header (non-critical)
                Object respHeaders = null;
                try {
                    respHeaders = XposedHelpers.callMethod(builder, "headers");
                } catch (Throwable th) {
                    // headers() method may not exist on all Response.Builder implementations
                }
                if (respHeaders != null) {
                    try {
                        Object headersBuilder = XposedHelpers.callMethod(respHeaders, "newBuilder");
                        XposedHelpers.callMethod(headersBuilder, "removeAll", "Content-Length");
                        Object newHeaders = XposedHelpers.callMethod(headersBuilder, "build");
                        XposedHelpers.callMethod(builder, "headers", newHeaders);
                        debugLog("[V91-RB] step6: headers cleaned");
                    } catch (Throwable th) {
                        debugLog("[V91-RB] step6-skip: header cleanup skipped: " + th.getMessage());
                    }
                }
            } catch (Throwable e) {
                debugLog("[V91-RB] step6-skip: header cleanup skipped: " + e.getMessage());
            }

            debugLog("[V91-RB] step7: calling build()...");
            Object result = XposedHelpers.callMethod(builder, "build");
            debugLog("[V91-RB] step7: build() OK, response rebuilt!");
            return result;

        } catch (Throwable e) {
            debugLog("[V91-RB] FAILED: " + e.getClass().getName() + ": " + e.getMessage());
            // Print stack trace elements
            StackTraceElement[] stack = e.getStackTrace();
            if (stack.length > 0) {
                debugLog("[V91-RB] at " + stack[0]);
            }
            return null;
        }
    }

    // ===================== V94: Pre-fetch GD API before request =====================

    /**
     * Pre-fetch GD API URLs for all songs in a player/url request.
     * This warms the cache in EAPIHelper so that afterHook's modifyPlayer
     * will find the cached URLs instantly without making its own API calls.
     * Called synchronously from beforeHookedMethod — blocks until GD returns.
     */
    private void prefetchGdForPlayerRequest(Object call) {
        try {
            List<Long> songIds = extractSongIdsFromCall(call);
            if (songIds == null || songIds.isEmpty()) {
                debugLog("[V94] player/url: could not extract song IDs, skipping prefetch");
                return;
            }
            debugLog("[V94] player/url: prefetching GD URLs for " + songIds.size() + " songs: " + songIds);

            for (Long songId : songIds) {
                String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                if (gdUrl != null) {
                    debugLog("[V94] Prefetch OK for song " + songId);
                } else {
                    debugLog("[V94] Prefetch FAILED for song " + songId);
                }
            }
        } catch (Exception e) {
            debugLog("[V94] prefetch error: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * Extract song IDs from an OkHttp Call by decrypting the NetEase EAPI params.
     */
    private List<Long> extractSongIdsFromCall(Object call) {
        try {
            Object request = getRequestFromCall(call);
            if (request == null) return null;

            // Get request body
            String bodyStr = null;
            Object body = XposedHelpers.callMethod(request, "body");
            if (body != null) {
                Class<?> bufferClass = findClassIfExists("okio.Buffer", appClassLoader);
                if (bufferClass != null) {
                    Object buffer = bufferClass.newInstance();
                    XposedHelpers.callMethod(body, "writeTo", buffer);
                    bodyStr = (String) XposedHelpers.callMethod(buffer, "readUtf8");
                }
            }

            if (bodyStr == null || bodyStr.isEmpty()) {
                debugLog("[V93] request body is empty, trying URL query string...");
                // Fallback: extract song IDs from the URL query string
                // Format: .../player/url/v1?ids=["1422992414_0"]&level=exhigh...
                List<Long> fromUrl = extractSongIdsFromUrl(call);
                if (fromUrl != null && !fromUrl.isEmpty()) {
                    debugLog("[V93] extracted " + fromUrl.size() + " song IDs from URL");
                    return fromUrl;
                }
                debugLog("[V93] body is empty and no IDs in URL either");
                return null;
            }

            // Parse form-encoded body to get "params"
            String params = null;
            String[] pairs = bodyStr.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf("=");
                if (eq > 0) {
                    String key = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String val = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    if ("params".equals(key)) {
                        params = val;
                        break;
                    }
                }
            }

            if (params == null) {
                debugLog("[V93] no 'params' field in request body: " + bodyStr.substring(0, Math.min(100, bodyStr.length())));
                return null;
            }

            // Decrypt the params
            String decrypted = NeteaseAES2.Decrypt(params);
            if (decrypted == null || decrypted.isEmpty()) {
                debugLog("[V93] decrypt returned empty");
                return null;
            }

            // Trim to JSON boundaries
            int start = decrypted.indexOf("{");
            int end = decrypted.lastIndexOf("}");
            if (start < 0 || end < 0 || start >= end) {
                debugLog("[V93] decrypted body is not valid JSON: " + decrypted.substring(0, Math.min(100, decrypted.length())));
                return null;
            }
            String jsonStr = decrypted.substring(start, end + 1);

            JSONObject json = new JSONObject(jsonStr);
            List<Long> songIds = new ArrayList<>();

            // Try "ids" field — can be JSONArray or string like "[123,456]"
            Object idsObj = json.opt("ids");
            if (idsObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) idsObj;
                for (int i = 0; i < arr.length(); i++) {
                    songIds.add(arr.getLong(i));
                }
            } else if (idsObj instanceof String) {
                String idsStr = ((String) idsObj).trim();
                // Could be "[123,456]" — try parsing as JSON array
                if (idsStr.startsWith("[")) {
                    JSONArray arr = new JSONArray(idsStr);
                    for (int i = 0; i < arr.length(); i++) {
                        songIds.add(arr.getLong(i));
                    }
                } else {
                    // Single ID as string
                    try { songIds.add(Long.parseLong(idsStr)); } catch (NumberFormatException ignored) {}
                }
            } else if (idsObj instanceof Number) {
                songIds.add(((Number) idsObj).longValue());
            }

            // Also check "id" field (singular)
            if (songIds.isEmpty() && json.has("id")) {
                songIds.add(json.getLong("id"));
            }

            return songIds.isEmpty() ? null : songIds;
        } catch (Exception e) {
            debugLog("[V93] extractSongIds error: " + e.getClass().getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Extract song IDs from URL query string — fallback when body is consumed.
     * URL format: .../player/url/v1?ids=["1422992414_0","316686_0"]&level=exhigh...
     * Each ID is "{songId}_0".
     */
    private List<Long> extractSongIdsFromUrl(Object call) {
        try {
            String url = getUrlFromCall(call);
            if (url == null || !url.contains("?")) {
                return null;
            }

            // Parse the query string
            String query = url.substring(url.indexOf("?") + 1);
            String[] params = query.split("&");
            String idsParam = null;
            for (String p : params) {
                if (p.startsWith("ids=")) {
                    idsParam = URLDecoder.decode(p.substring(4), "UTF-8");
                    break;
                }
            }

            if (idsParam == null) {
                return null;
            }

            // idsParam should be something like ["1422992414_0","316686_0"]
            JSONArray arr = new JSONArray(idsParam);
            List<Long> songIds = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String idStr = arr.getString(i);
                // Strip the trailing "_0" suffix: "1422992414_0" -> 1422992414
                int underscore = idStr.lastIndexOf('_');
                String rawId = (underscore > 0) ? idStr.substring(0, underscore) : idStr;
                try {
                    songIds.add(Long.parseLong(rawId));
                } catch (NumberFormatException ignored) {
                    debugLog("[V93] URL song ID parse failed for: " + rawId);
                }
            }

            return songIds.isEmpty() ? null : songIds;
        } catch (Exception e) {
            debugLog("[V93] extractSongIdsFromUrl error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the OkHttp Request object from a Call object via reflection.
     */
    private Object getRequestFromCall(Object call) {
        try {
            // Try originalRequest field first (RealCall)
            java.lang.reflect.Field rf = call.getClass().getDeclaredField("originalRequest");
            rf.setAccessible(true);
            return rf.get(call);
        } catch (Exception e1) {
            try {
                return XposedHelpers.callMethod(call, "request");
            } catch (Exception e2) {
                return null;
            }
        }
    }

    // ===================== End V94 pre-fetch =====================

    /**
     * Modify a single song privilege object: clear ALL restrictions
     * @return true if the song was modified
     */
    private static boolean modifySingleSongPrivilege(JSONObject song) {
        try {
            int fee = song.optInt("fee", 0);
            int flag = song.optInt("flag", 0);
            int payed = song.optInt("payed", 0);
            int pl = song.optInt("pl", 0);
            int dl = song.optInt("dl", 0);
            int fl = song.optInt("fl", 0);
            int sp = song.optInt("sp", 0);
            boolean cs = song.optBoolean("cs", false);
            boolean hasFreeTrial = song.has("freeTrialInfo") && !song.isNull("freeTrialInfo");

            // Skip cloud disk songs (flag & 0x8 != 0)
            if ((flag & 0x8) != 0) return false;

            // Only modify if the song has restrictions
            if (fee == 0 && pl > 0 && dl > 0 && !hasFreeTrial) return false;

            long songId = song.optLong("id", 0);

            debugLog("[V91-PRI] id=" + songId + " fee=" + fee + " flag=" + flag +
                    " payed=" + payed + " pl=" + pl + " dl=" + dl + " fl=" + fl +
                    " sp=" + sp + " cs=" + cs + " hasFreeTrial=" + hasFreeTrial);

            // Clear ALL restrictions
            song.put("fee", 0);
            song.put("flag", 0);
            song.put("payed", 1);      // Mark as paid
            song.put("pl", 320000);     // Play level: 320kbps (standard)
            song.put("dl", 320000);     // Download level: 320kbps
            song.put("fl", 999000);     // Free level: highest
            song.put("sp", 7);          // Keep sp as-is or set to 7
            song.put("cs", true);       // Can stream
            song.put("st", 0);          // No special treatment
            song.put("subp", 1);        // Sub privilege
            song.put("toast", false);   // No VIP purchase prompt
            song.put("cp", 1);          // Can play
            song.put("preSell", false); // Not pre-sell

            // Ensure maxbr is high
            if (song.has("maxbr")) {
                song.put("maxbr", 999000);
            }
            if (song.has("playMaxbr")) {
                song.put("playMaxbr", 999000);
            }
            if (song.has("downloadMaxbr")) {
                song.put("downloadMaxbr", 999000);
            }

            // Clear freeTrialInfo
            song.put("freeTrialInfo", JSONObject.NULL);

            // VIP songs (fee>0) or trial songs → replace URL via GD API
            if ((hasFreeTrial || fee > 0) && songId > 0) {
                debugLog("[V91-PRI] id=" + songId + " needs GD URL (freeTrial=" + hasFreeTrial + " fee=" + fee + ")");
                String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                if (gdUrl != null) {
                    song.put("url", gdUrl);
                    debugLog("[V91-PRI] id=" + songId + " GD API URL set OK => " + gdUrl);
                } else {
                    debugLog("[V91-PRI] id=" + songId + " GD API returned NULL");
                }
            }

            return true;
        } catch (Exception e) {
            debugLog("[V91-PRI] modifySong error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Modify the /eapi/song/enhance/privilege response body
     */
    private static String modifyPrivilegeResponse(String bodyString) {
        try {
            JSONObject root = new JSONObject(bodyString);
            // v3/song/detail has no "code" field, skip code check in that case
            int code = root.optInt("code", 200);
            if (code != 200 && root.has("code")) {
                debugLog("[V91-PRI] response code=" + code + ", skipping");
                return null;
            }

            Object dataObj = root.opt("data");
            // v3/song/detail uses "songs" instead of "data"
            if (dataObj == null) {
                dataObj = root.opt("songs");
                if (dataObj != null) {
                    debugLog("[V91-PRI] using 'songs' key (v3/song/detail format)");
                }
            }
            if (dataObj == null) {
                debugLog("[V91-PRI] no data or songs field");
                return null;
            }

            int modifiedCount = processSongArray(dataObj);

            if (modifiedCount > 0) {
                debugLog("[V91-PRI] Modified " + modifiedCount + " songs, serializing...");
                String result = root.toString();
                debugLog("[V91-PRI] Serialized OK, len=" + result.length());
                return result;
            } else {
                debugLog("[V91-PRI] No songs needed modification");
                return null;
            }
        } catch (Exception e) {
            debugLog("[V91-PRI] modifyPrivilegeResponse error: " + e.getMessage());
            return null;
        }
    }

    /** Process a data/songs array or object, modifying song privileges */
    private static int processSongArray(Object dataObj) {
        int count = 0;
        try {
            if (dataObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) dataObj;
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        if (modifySingleSongPrivilege(arr.getJSONObject(i))) {
                            count++;
                        }
                    } catch (Exception ignored) {}
                }
            } else if (dataObj instanceof JSONObject) {
                JSONObject data = (JSONObject) dataObj;
                if (modifySingleSongPrivilege(data)) {
                    count++;
                }
                Iterator<String> keys = data.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = data.opt(key);
                    if (val instanceof JSONArray) {
                        JSONArray arr = (JSONArray) val;
                        for (int i = 0; i < arr.length(); i++) {
                            try {
                                if (modifySingleSongPrivilege(arr.getJSONObject(i))) {
                                    count++;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            debugLog("[V91-PRI] processSongArray error: " + e.getMessage());
        }
        return count;
    }

    /**
     * Modify the /eapi/song/enhance/location/info response body
     * This endpoint returns the actual play URL for a song
     */
    private static String modifyLocationInfoResponse(String bodyString) {
        try {
            JSONObject root = new JSONObject(bodyString);
            if (root.optInt("code") != 200) {
                debugLog("[V91-LOC] response code=" + root.optInt("code") + ", skipping");
                return null;
            }

            Object dataObj = root.opt("data");
            if (dataObj == null) {
                debugLog("[V91-LOC] no data field");
                return null;
            }

            int modifiedCount = 0;

            // data can be a single object or array
            if (dataObj instanceof JSONArray) {
                JSONArray arr = (JSONArray) dataObj;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject song = arr.getJSONObject(i);
                    if (modifyLocationSong(song)) {
                        modifiedCount++;
                    }
                }
            } else if (dataObj instanceof JSONObject) {
                JSONObject data = (JSONObject) dataObj;
                if (modifyLocationSong(data)) {
                    modifiedCount++;
                }
            }

            if (modifiedCount > 0) {
                debugLog("[V91-LOC] Modified " + modifiedCount + " songs in location/info");
                return root.toString();
            } else {
                debugLog("[V91-LOC] No songs needed modification in location/info");
                return null;
            }
        } catch (Exception e) {
            debugLog("[V91-LOC] modifyLocationInfoResponse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Modify a single song in location/info response
     */
    private static boolean modifyLocationSong(JSONObject song) {
        try {
            int fee = song.optInt("fee", 0);
            int flag = song.optInt("flag", 0);
            long songId = song.optLong("id", 0);
            String url = song.optString("url", null);
            boolean hasFreeTrial = song.has("freeTrialInfo") && !song.isNull("freeTrialInfo");

            debugLog("[V91-LOC] id=" + songId + " fee=" + fee + " flag=" + flag +
                    " hasFreeTrial=" + hasFreeTrial +
                    " url=" + (url != null ? url.substring(0, Math.min(80, url.length())) : "null"));

            // Skip cloud disk songs
            if ((flag & 0x8) != 0) return false;

            boolean modified = false;

            // If fee != 0 or has freeTrial, modify
            if (fee != 0 || hasFreeTrial || (url == null && songId > 0)) {
                song.put("fee", 0);
                song.put("flag", 0);
                song.put("payed", 1);
                song.put("freeTrialInfo", JSONObject.NULL);
                song.put("pl", 320000);
                song.put("dl", 320000);
                song.put("fl", 999000);
                song.put("cs", true);
                song.put("st", 0);
                song.put("toast", false);

                if (song.has("code")) {
                    song.put("code", 200);
                }

                // VIP songs (fee>0) with null/trial URL → replace via GD API
                if ((url == null || hasFreeTrial || fee > 0) && songId > 0) {
                    debugLog("[V91-LOC] id=" + songId + " fetching GD API (urlNull=" + (url==null) + " freeTrial=" + hasFreeTrial + " fee=" + fee + ")");
                    String gdUrl = EAPIHelper.fetchUrlFromGD(songId, 999);
                    if (gdUrl != null) {
                        song.put("url", gdUrl);
                        debugLog("[V91-LOC] id=" + songId + " GD API URL set OK => " + gdUrl);
                    } else {
                        debugLog("[V91-LOC] id=" + songId + " GD API returned NULL");
                    }
                }
                modified = true;
            }

            return modified;
        } catch (Exception e) {
            debugLog("[V91-LOC] modifyLocationSong error: " + e.getMessage());
            return false;
        }
    }

    public EAPIHook(Context context, int versionCode, ClassLoader cl) {
        this.appClassLoader = cl;
        EAPIHelper.setContext(context);
        debugLog("=== EAPIHook v91 FULL PRIVILEGE + ENHANCED DEBUG init, versionCode=" + versionCode + " ===");

        hookRealCallExecute();

        debugLog("=== EAPIHook v91 init complete ===");
    }

    private void hookRealCallExecute() {
        debugLog("[V91] Hooking RealCall.execute()...");

        String[] callClassNames = {
            "okhttp3.internal.connection.RealCall",
            "okhttp3.internal.http.RealCall",
            "okhttp3.RealCall",
        };

        Class<?> callClass = null;
        String foundName = null;

        for (String name : callClassNames) {
            Class<?> cls = findClassIfExists(name, appClassLoader);
            if (cls != null) {
                callClass = cls;
                foundName = name;
                break;
            }
        }

        if (callClass == null) {
            callClass = findClassIfExists("okhttp3.Call", appClassLoader);
            foundName = "okhttp3.Call (interface)";
        }

        if (callClass == null) {
            debugLog("[V91] No Call class found!");
            return;
        }

        debugLog("[V91] Found: " + foundName);

        for (Method m : callClass.getDeclaredMethods()) {
            if (m.getName().equals("execute") && m.getParameterTypes().length == 0) {
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String url = getUrlFromCall(param.thisObject);
                                if (url != null && url.contains("player/url")) {
                                    // V94: Pre-fetch GD API URLs BEFORE request goes out
                                    // This blocks until GD returns, warming the cache
                                    prefetchGdForPlayerRequest(param.thisObject);
                                    // Strip trialMode so NetEase doesn't return a trial-limited URL
                                    if (url.contains("trialMode=")) {
                                        String newUrl = stripTrialMode(url);
                                        modifyRequestUrl(param.thisObject, newUrl);
                                        debugLog("[V94] trialMode stripped from player/url");
                                    }
                                }
                            } catch (Exception e) {
                                debugLog("[V94] beforeHook error: " + e.getMessage());
                            }
                        }
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String url = getUrlFromCall(param.thisObject);
                                if (url == null) return;

                                // DEBUG: log every URL to find where privilege check happens
                                if (url.contains("eapi") || url.contains("api") || url.contains("music.163.com")) {
                                    debugLog("[V91-URL] " + url.substring(0, Math.min(200, url.length())));
                                }

                                // NEW: Intercept v3/song/detail (v9.5.37 uses this instead of song/enhance/privilege)
                                if (url.contains("v3/song/detail")) {
                                    debugLog("[V91] >>> v3/song/detail caught");
                                    Object resp = param.getResult();
                                    if (resp != null) {
                                        Object body = XposedHelpers.callMethod(resp, "body");
                                        if (body != null) {
                                            String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                            if (bodyString != null) {
                                                debugLog("[V91] v3/song/detail body len=" + bodyString.length() +
                                                    " preview=" + bodyString.substring(0, Math.min(500, bodyString.length())));
                                                // Try to modify privilege in this response too
                                                String modifiedBody = modifyPrivilegeResponse(bodyString);
                                                if (modifiedBody != null) {
                                                    Object newResponse = rebuildResponse(resp, modifiedBody, appClassLoader);
                                                    if (newResponse != null) {
                                                        param.setResult(newResponse);
                                                        debugLog("[V91] >>> v3/song/detail REPLACED OK");
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Intercept privilege endpoint
                                if (url.contains("song/enhance/privilege")) {
                                    debugLog("[V91] >>> privilege caught");
                                    Object response = param.getResult();
                                    if (response == null) {
                                        debugLog("[V91] privilege response=null, skip");
                                        return;
                                    }

                                    Object body = XposedHelpers.callMethod(response, "body");
                                    if (body == null) {
                                        debugLog("[V91] privilege body=null, skip");
                                        return;
                                    }

                                    String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                    if (bodyString == null || bodyString.isEmpty()) {
                                        debugLog("[V91] privilege bodyString empty, skip");
                                        return;
                                    }

                                    debugLog("[V91] privilege body len=" + bodyString.length());

                                    String modifiedBody = modifyPrivilegeResponse(bodyString);
                                    debugLog("[V91] modifyPrivilegeResponse returned: " + (modifiedBody != null ? "len=" + modifiedBody.length() : "null"));
                                    if (modifiedBody != null) {
                                        debugLog("[V91] calling rebuildResponse...");
                                        Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                        debugLog("[V91] rebuildResponse returned: " + (newResponse != null ? "OK" : "null"));
                                        if (newResponse != null) {
                                            debugLog("[V91] calling setResult...");
                                            param.setResult(newResponse);
                                            debugLog("[V91] >>> privilege REPLACED OK");
                                        } else {
                                            debugLog("[V91] rebuildResponse failed for privilege");
                                        }
                                    }
                                }
                                // Intercept location/info endpoint
                                else if (url.contains("song/enhance/location")) {
                                    debugLog("[V91] >>> location/info caught");
                                    Object response = param.getResult();
                                    if (response == null) return;

                                    Object body = XposedHelpers.callMethod(response, "body");
                                    if (body == null) return;

                                    String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                    if (bodyString == null || bodyString.isEmpty()) return;

                                    debugLog("[V91] location/info body len=" + bodyString.length() +
                                            " preview=" + bodyString.substring(0, Math.min(300, bodyString.length())));

                                    String modifiedBody = modifyLocationInfoResponse(bodyString);
                                    if (modifiedBody != null) {
                                        Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                        if (newResponse != null) {
                                            param.setResult(newResponse);
                                            debugLog("[V91] >>> location/info REPLACED OK");
                                        }
                                    }
                                }
                                // Intercept player/url endpoint — strip trial info & get full URL
                                else if (url.contains("player/url")) {
                                    debugLog("[V91] >>> player/url caught");
                                    Object response = param.getResult();
                                    if (response != null) {
                                        Object body = XposedHelpers.callMethod(response, "body");
                                        if (body != null) {
                                            String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                            if (bodyString != null && !bodyString.isEmpty()) {
                                                debugLog("[V91] player/url body len=" + bodyString.length() +
                                                    " preview=" + bodyString.substring(0, Math.min(300, bodyString.length())));
                                                String modifiedBody = EAPIHelper.modifyPlayer(bodyString);
                                                if (modifiedBody != null) {
                                                    Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                                    if (newResponse != null) {
                                                        param.setResult(newResponse);
                                                        debugLog("[V91] >>> player/url REPLACED OK");
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // Intercept vipmall/interest/trialsong/listen — block trial tracking
                                else if (url.contains("vipmall/interest/trialsong/listen")) {
                                    debugLog("[V91] >>> trialsong/listen caught, blocking trial report");
                                    Object response = param.getResult();
                                    if (response != null) {
                                        // Replace response with {"code":200} to fake trial success & prevent time limit
                                        String fakeBody = "{\"code\":200,\"data\":{}}";
                                        Object newResponse = rebuildResponse(response, fakeBody, appClassLoader);
                                        if (newResponse != null) {
                                            param.setResult(newResponse);
                                            debugLog("[V91] >>> trialsong/listen BLOCKED OK");
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                debugLog("[V91] execute hook error: " + e.getClass().getName() + ": " + e.getMessage());
                                StackTraceElement[] stack = e.getStackTrace();
                                if (stack.length > 0) {
                                    debugLog("[V91] at " + stack[0]);
                                }
                            }
                        }
                    });
                    debugLog("[V91] Hooked " + foundName + ".execute()");
                } catch (Exception e) {
                    debugLog("[V91] Failed to hook execute(): " + e.getMessage());
                }
                break;
            }
        }

        // Also hook enqueue() for async requests — use Callback proxy wrapping (v92)
        for (Method m : callClass.getDeclaredMethods()) {
            if (m.getName().equals("enqueue") && m.getParameterTypes().length == 1) {
                try {
                    final Class<?> callbackInterface = findClassIfExists("okhttp3.Callback", appClassLoader);
                    if (callbackInterface == null) {
                        debugLog("[V91] enqueue: Callback interface not found, skipping async hook");
                        break;
                    }

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                String url = getUrlFromCall(param.thisObject);

                                if (url != null && url.contains("player/url")) {
                                    // V94: Pre-fetch GD API URLs BEFORE async request goes out
                                    prefetchGdForPlayerRequest(param.thisObject);
                                    // Strip trialMode
                                    if (url.contains("trialMode=")) {
                                        String newUrl = stripTrialMode(url);
                                        modifyRequestUrl(param.thisObject, newUrl);
                                        debugLog("[V94] enqueue trialMode stripped");
                                        url = newUrl;
                                    }
                                }
                                if (url == null || param.args[0] == null) return;

                                boolean isTarget = url.contains("song/enhance/privilege") ||
                                                   url.contains("song/enhance/location") ||
                                                   url.contains("player/url") ||
                                                   url.contains("v3/song/detail") ||
                                                   url.contains("vipmall/interest/trialsong/listen");
                                if (!isTarget) return;

                                final Object originalCallback = param.args[0];
                                final String finalUrl = url;

                                // Wrap callback in Proxy to intercept onResponse reliably
                                Object proxyCallback = Proxy.newProxyInstance(
                                    callbackInterface.getClassLoader(),
                                    new Class[]{callbackInterface},
                                    new InvocationHandler() {
                                        @Override
                                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                            if (method.getName().equals("onResponse") && args != null && args.length >= 2) {
                                                try {
                                                    Object response = args[1];
                                                    if (response != null) {
                                                        Object body = XposedHelpers.callMethod(response, "body");
                                                        if (body != null) {
                                                            String bodyString = (String) XposedHelpers.callMethod(body, "string");
                                                            if (bodyString != null && !bodyString.isEmpty()) {
                                                                debugLog("[V91] >>> async " + extractPath(finalUrl) + " caught, len=" + bodyString.length());

                                                                String modifiedBody = null;
                                                                if (finalUrl.contains("song/enhance/privilege") || finalUrl.contains("v3/song/detail")) {
                                                                    modifiedBody = modifyPrivilegeResponse(bodyString);
                                                                } else if (finalUrl.contains("player/url")) {
                                                                    debugLog("[V91] async player/url body: " + bodyString.substring(0, Math.min(300, bodyString.length())));
                                                                    modifiedBody = EAPIHelper.modifyPlayer(bodyString);
                                                                } else if (finalUrl.contains("vipmall/interest/trialsong/listen")) {
                                                                    debugLog("[V91] async trialsong/listen blocking");
                                                                    modifiedBody = "{\"code\":200,\"data\":{}}";
                                                                } else {
                                                                    modifiedBody = modifyLocationInfoResponse(bodyString);
                                                                }

                                                                if (modifiedBody != null) {
                                                                    Object newResponse = rebuildResponse(response, modifiedBody, appClassLoader);
                                                                    if (newResponse != null) {
                                                                        args[1] = newResponse;
                                                                        debugLog("[V91] >>> async " + extractPath(finalUrl) + " REPLACED OK");
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    debugLog("[V91] async proxy error: " + e.getClass().getName() + ": " + e.getMessage());
                                                }
                                            }
                                            // Pass through to original callback
                                            try {
                                                return method.invoke(originalCallback, args);
                                            } catch (Exception e) {
                                                debugLog("[V91] async proxy passthrough error: " + e.getMessage());
                                                return null;
                                            }
                                        }
                                    }
                                );

                                param.args[0] = proxyCallback;
                                debugLog("[V91] enqueue: wrapped callback for " + extractPath(url));
                            } catch (Exception e) {
                                debugLog("[V91] enqueue beforeHook error: " + e.getClass().getName() + ": " + e.getMessage());
                            }
                        }
                    });
                    debugLog("[V91] Hooked " + foundName + ".enqueue() (proxy wrapper)");
                } catch (Exception e) {
                    debugLog("[V91] Failed to hook enqueue(): " + e.getMessage());
                }
                break;
            }
        }
    }

    /**
     * Extract the last path segment from a URL for logging
     */
    private static String extractPath(String url) {
        try {
            int idx = url.lastIndexOf('/');
            if (idx >= 0) {
                String path = url.substring(idx + 1);
                int qm = path.indexOf('?');
                if (qm >= 0) path = path.substring(0, qm);
                return path;
            }
        } catch (Exception ignored) {}
        return url;
    }

    /**
     * Remove trialMode=1 from player/url request URLs
     */
    private static String stripTrialMode(String url) {
        url = url.replaceAll("[&?]trialMode=[^&]*", "");
        url = url.replace("?&", "?");
        return url;
    }

    /**
     * Replace the original request URL in an OkHttp RealCall via reflection
     */
    private static void modifyRequestUrl(Object callObject, String newUrl) {
        try {
            java.lang.reflect.Field requestField = callObject.getClass().getDeclaredField("originalRequest");
            requestField.setAccessible(true);
            Object originalRequest = requestField.get(callObject);

            // Request.newBuilder().url(newUrl).build()
            Object builder = originalRequest.getClass().getMethod("newBuilder").invoke(originalRequest);
            builder.getClass().getMethod("url", String.class).invoke(builder, newUrl);
            Object newRequest = builder.getClass().getMethod("build").invoke(builder);

            requestField.set(callObject, newRequest);
        } catch (Exception e) {
            debugLog("[V91] modifyRequestUrl error: " + e.getMessage());
        }
    }
}
