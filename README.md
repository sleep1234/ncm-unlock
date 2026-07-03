# NCM Unlock

网易云音乐 VIP 歌曲解锁独立 Xposed 模块。

从 [dolby_beta](https://github.com/nining377/dolby_beta) 剥离核心解锁功能，去除广告移除、UI 美化、音源代理等无关代码，保持轻量。

## 功能

- **VIP 歌曲解锁** — 拦截 EAPI 请求，通过 GD Studio API 替换试听 URL 为完整播放地址
- **黑胶 VIP 伪装** — 伪造 vipType=100、音乐包=220，有效期+1年
- **不变灰** — 强制显示所有歌曲（包括无版权歌曲）
- **音质限制解除** — 最高音质 999000 解锁
- **Toast 提示** — 播放 VIP 歌曲时显示替换状态

## 技术原理

### 核心流程

```
播放 VIP 歌曲
    ↓
EAPIHook 拦截 OkHttp RealCall.execute()/enqueue()
    ↓
识别 player/url 请求
    ↓
modifyPlayer 解析响应 JSON
    ↓
检测 fee > 0 或 freeTrialInfo 存在
    ↓
调用 GD Studio API 获取完整 URL
    ↓
替换 URL + 更新 br/size/type/encodeType
    ↓
rebuildResponse 返回修改后的响应
```

### 关键发现（V96 修复）

**问题**：替换 GD URL 后歌曲仍 3 秒跳过。

**根因**：`modifyPlayer` 只替换了 `url` 字段，但 `type`、`encodeType`、`br`（码率）、`size`（大小）仍保留试听片段的值。播放器检测到元数据不一致（试听写的 128kbps/mp3，实际拿到 989kbps/flac），退回试听模式。

**修复**：替换 URL 时同步更新四个字段：
```java
bean.setUrl(gd.url);
bean.setBr(gd.br);      // 码率
bean.setSize(gd.size);   // 文件大小
bean.setType("flac");    // 文件类型
bean.setEncodeType("flac"); // 编码类型
```

### GD Studio API

- 端点：`https://music-api.gdstudio.xyz/api.php`
- 参数：`types=url&source=netease&id={songId}&br={bitrate}`
- 返回：`{"url": "...", "br": 989, "size": 23811009}`
- 缓存 TTL：2 分钟（CDN URL 有过期时间）
- 重试：最多 3 次（处理 503 错误）

### Hook 端点

| 端点 | 作用 |
|------|------|
| `song/enhance/privilege` | 修改权限响应，清除 fee/flag/payed |
| `song/enhance/player/url/v1` | 替换播放 URL 为 GD API 地址 |
| `song/enhance/location/info` | 修改播放 URL（备用路径） |
| `v3/song/detail` | 修改歌曲详情中的权限字段 |
| `vipmall/interest/trialsong/listen` | 阻断试听上报（返回 code:200） |

## 与原版 dolby_beta 的区别

| 功能 | dolby_beta | ncm-unlock |
|------|-----------|------------|
| VIP 歌曲解锁 | ✅ | ✅ |
| 黑胶 VIP 伪装 | ✅ | ✅ |
| 不变灰 | ✅ | ✅ |
| 音质限制解除 | ✅ | ✅ |
| 音源代理 | ✅ | ❌ |
| 广告移除 | ✅ | ❌ |
| UI 美化 | ✅ | ❌ |
| 自动签到 | ✅ | ❌ |
| 下载 MD5 校验 | ✅ | ❌（简化为空实现） |
| 设置页面 | ✅ | ❌（所有功能默认启用） |
| HotXposed 热加载 | ✅ | ❌ |

## 依赖

- Android SDK 34
- JDK 11
- Xposed API 82
- Gson 2.8.9

## 构建

```bash
export JAVA_HOME=/path/to/jdk11
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 安装

1. 安装 APK 到手机
2. 在 LSPosed 中启用模块
3. 勾选「网易云音乐」作用域
4. 强制停止网易云音乐，重新打开

## 致谢

- [dolby_beta](https://github.com/nining377/dolby_beta) — 原始模块
- [GD Studio](https://music-api.gdstudio.xyz) — 音源 API

## 许可证

MIT License
