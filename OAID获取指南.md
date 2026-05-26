# 各平台 OAID 获取指南

---

## 一、APK（Android 应用）

### 1.1 集成 HMS Ads Kit

在 `build.gradle` 中添加依赖：

```groovy
dependencies {
    implementation 'com.huawei.hms:ads-identifier:3.4.66.300'
    implementation 'com.huawei.hms:ads-installreferrer:3.4.66.300'
}
```

### 1.2 原生获取代码

```java
import com.huawei.hms.ads.identifier.AdvertisingIdClient;
import com.huawei.hms.ads.identifier.AdvertisingIdClient.Info;

public class OAIDHelper {
    public static String getOAID(Context context) {
        try {
            // 必须在子线程中调用
            Info info = AdvertisingIdClient.getAdvertisingIdInfo(context);
            return info.getId();  // 返回 OAID
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }
}
```

### 1.3 Unity 调用原生

```csharp
public static string GetOAID()
{
    using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
    using (var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity"))
    using (var helper = new AndroidJavaObject("com.yourgame.OAIDHelper"))
    {
        return helper.CallStatic<string>("getOAID", activity);
    }
}
```

### 1.4 Install Referrer（可选增强）

```java
import com.huawei.hms.ads.installreferrer.api.InstallReferrerClient;
// 用于获取应用安装来源，配合 OAID 做双重归因
```

### 1.5 注意事项

- OAID 不需要额外权限，但受用户「限制广告跟踪」开关影响
- 开关关闭时 OAID 返回全 0 字符串
- Install Referrer API 仅 Android 10+ 可用

---

## 二、HAP（鸿蒙应用）

### 2.1 权限声明

在 `module.json5` 中添加：

```json
{
  "module": {
    "requestPermissions": [
      {
        "name": "ohos.permission.APP_TRACKING_CONSENT",
        "reason": "$string:tracking_reason",
        "usedScene": {
          "abilities": ["EntryAbility"],
          "when": "always"
        }
      },
      {
        "name": "ohos.permission.INTERNET"
      }
    ]
  }
}
```

### 2.2 获取 OAID

```typescript
import { identifier } from '@kit.AdsKit';

export async function getOAID(): Promise<string> {
    try {
        const oaid = await identifier.getOAID();
        return oaid || '';
    } catch (err) {
        console.error('[OAID] 获取失败:', JSON.stringify(err));
        return '';
    }
}
```

### 2.3 注意事项

- 鸿蒙 5.0+ 需要申请 `ohos.permission.APP_TRACKING_CONSENT` 权限
- 用户可以在「设置 → 隐私 → 广告与隐私」中管理 OAID 授权
- 同一设备不同 App 获取到的 OAID 相同

---

## 三、RPK（华为快游戏）

### 3.1 方案 A：qg.getOAID()（首选）

```javascript
// 在 manifest.json 中配置
{
  "features": [
    { "name": "system.device" }
  ]
}

// 获取 OAID
qg.getOAID({
    success: function(res) {
        console.log("OAID: " + res.oaid);
    },
    fail: function(err) {
        console.log("获取失败: " + JSON.stringify(err));
    }
});
```

**限制条件**：
- 必须在**华为官方快应用加载器**中运行
- 如果在联盟版预览器或其他加载器中，API 可能不可用
- 需要手机系统支持 OAID（EMUI/HarmonyOS）

### 3.2 方案 B：qg.getLaunchOptionsSync()（备选）

```javascript
// 获取启动参数中的归因信息
try {
    const opts = qg.getLaunchOptionsSync();
    // opts.query 可能包含:
    // adid=123456
    // creativeid=654321
    // creativetype=3
    // clickid=EPHk9cX3pv4CGJax4ZENKI7w4MDev_4C
    console.log("启动参数:", JSON.stringify(opts.query));
    
    // 将 clickid 作为设备标识上报
    const clickid = opts.query.clickid || '';
} catch (e) {
    console.log("获取启动参数失败");
}
```

### 3.3 方案 C：qa.getId()（降级）

```javascript
qa.getId({
    type: ['device'],
    success: function(data) {
        // data.device = AAID（应用级标识，每App不同）
        console.log("AAID: " + data.device);
    },
    fail: function(data, code) {
        console.log("获取失败, code=" + code);
    }
});
```

### 3.4 RPK 推荐策略

```
1. 尝试 qg.getOAID() → 成功 → 使用 OAID
                    → 失败(API不存在) → 尝试方案B
                    
2. 尝试 getLaunchOptionsSync() → 获取到 clickid/adid → 使用 clickid 上报
                                → 无参数 → 尝试方案C
                                
3. qa.getId() → 获取 AAID → 作为备选标识上报
                              → 同时填写 fingerprint 字段
```

---

## 四、各平台能力对比

| 能力 | APK | HAP | RPK |
|------|-----|-----|-----|
| OAID | ✅ HMS Ads Kit | ✅ @kit.AdsKit | ⚠️ 限华为加载器 |
| ATT 权限弹窗 | 不需要 | 鸿蒙 5.0+ 需要 | 不需要 |
| 安装来源 | ✅ Install Referrer | ✅ 应用归因服务 | ❌ |
| 备选 ID | GAID | - | AAID / clickid |
| 指纹信息可用 | IP+UA 可用 | IP+UA 可用 | IP+UA 可用 |

---

## 五、获取测试手机 OAID

联调测试时需要知道测试手机的 OAID：

1. 打开手机「**设置**」
2. 进入「**隐私**」→「**广告与隐私**」
3. 点击「**更多信息**」查看 OAID
4. 将此 OAID 填入鲸鸿动能后台的联调界面

> 路径可能因 EMUI/HarmonyOS 版本略有不同，也可以搜索「广告标识符」
