# WayMap

Android 地圖函式庫（Kotlin + Jetpack Compose + MapLibre GL）。從 [ChangeGpsAnyway](https://github.com/ShiKi5473/ChangeGpsAnyway) 的地圖功能拆出並泛化，供 [WayGuard](https://github.com/ShiKi5473/WayGuard) 等 App 共用。

| 模組 | 座標 | 內容 |
|---|---|---|
| `:geo` | `com.github.ShiKi5473.WayMap:geo:<tag>` | 純 Kotlin/JVM：`LatLng`、`GeoUtils`（haversine、bearing、destinationPoint、projectOnSegment、angleDiff…）。JVM 工具也能用。 |
| `:waymap` | `com.github.ShiKi5473.WayMap:waymap:<tag>` | Android：`WayMap` composable、`MarkerIcons`、`PhotonGeocoder` 地名搜尋。透過 `api` 帶入 `:geo`。 |

## 使用

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
implementation("com.github.ShiKi5473.WayMap:waymap:v0.1.0")
```

```kotlin
WayMap(
    modifier = Modifier.fillMaxSize(),
    icons = listOf(MapIcon("pos") { MarkerIcons.dot(Color.RED, 56) }),
    markers = listOf(MapMarker("me", here, icon = "pos")),
    lines = listOf(MapLine("route", path, color = "#1976D2", casingColor = "#FFFFFF")),
    pointLayers = listOf(PointLayer("cameras", points, minZoom = 10f)),
    userLocation = UserLocationMode.FOLLOW_BEARING,
    onPointTap = { layerId, pointId -> /* … */ },
    onFollowDismissed = { /* 使用者拖動地圖，改回 SHOW 並顯示「回到目前位置」 */ },
)
```

- `markers`：少量、可互動（依 `id` 差異更新；只改位置/圖示時就地移動）。
- `lines`：折線，可加白色 casing 底線。以單一 GeoJSON source + 兩個 `LineLayer`（casing 在下）繪製，只有清單變動時才重新上傳。`linesBelowLabels = true`（預設）時畫在底圖文字標註之下；設為 `false`（或樣式沒有文字圖層）則畫在 `pointLayers` 之下。繪製順序恆為 lines < pointLayers < markers。
- `pointLayers`：大量靜態點（GeoJSON source + SymbolLayer，數千點仍流暢），畫在 lines 之上、markers 之下。
- `userLocation`：`HIDDEN` / `SHOW`（首次定位置中一次，不接管鏡頭）/ `FOLLOW` / `FOLLOW_BEARING`（行車用，隨車頭旋轉）。
- 預設底圖為免金鑰的 OpenFreeMap Liberty；`styleUrl` 可替換，執行中改變 `styleUrl` 會重新載入樣式並自動重畫 icons / markers / lines / pointLayers（`WayMapDefaults.STYLE_URL_DARK` 為深色樣式）。
- 地圖顯示時請保持 `WayMap` 在組合樹中（其他畫面疊在上方），避免定位元件尚有未完成的定位時被銷毀而閃退。

### 鏡頭範圍（`fitTo`）

```kotlin
var fit by remember { mutableStateOf<FitRequest?>(null) }
WayMap(fitTo = fit /* … */)
// 需要時：fit = FitRequest(routePoints, paddingDp = 48, maxZoom = 17.0, animate = true)
```

`FitRequest` 與 `CenterRequest` 一樣**不是 data class**，每個新實例都是一次新請求。0 個點不做事；1 個點（或全部相同）以 `maxZoom` 置中；其餘點以 `paddingDp` 邊距框住，縮放不超過 `maxZoom`。地圖尚未完成版面配置時會延後到有尺寸再套用。

### App 推送定位（`userFix`）

```kotlin
WayMap(
    userLocation = UserLocationMode.FOLLOW_BEARING,
    userFix = UserFix(pos, bearingDeg = heading, speedMps = speed, accuracyM = acc),
)
```

`userFix` 非 null 且 `userLocation != HIDDEN`（且已授權定位）時，定位點改由 App 提供（例如貼路後的位置），MapLibre 內建的定位引擎會暫停；`userFix` 回到 null 時自動恢復。`UserFix` 內容改變才會推送；內容不變（停車）時每 10 秒重送一次，避免定位圖示被標成過期。`SHOW` 模式的「首次定位置中」改以第一個推送的 fix 為準。bearing / speed / accuracy 為 null 時不設定。

### 行車跟隨：依車速縮放與傾斜

```kotlin
WayMap(
    userLocation = UserLocationMode.FOLLOW_BEARING,
    followZoomForSpeed = { kmh -> if (kmh > 80) 15.0 else if (kmh > 40) 16.0 else 17.0 },
    followTilt = 45.0,
)
```

僅在 `FOLLOW_BEARING` 生效。車速取自 `userFix.speedMps`，否則取 LocationComponent 最後一筆定位的速度；每次重組時評估，目標縮放與目前差 >= 0.25 且距上次自動調整 >= 3 秒才會以 1 秒動畫調整。`followTilt` 在進入 `FOLLOW_BEARING` 時套用，離開時自動轉回 0。

### 載入失敗與離線快取

- `onMapLoadError = { message -> … }`：地圖或樣式載入失敗（例如離線且無快取）時回呼。
- `WayMapCache.setAmbientCacheSize(context, bytes) { error -> … }`：設定 MapLibre 自動快取上限；已瀏覽過區域的圖磚在此容量內可離線使用（`error` 為 null 代表成功）。
- 系統記憶體不足（`onTrimMemory` >= `TRIM_MEMORY_RUNNING_LOW`，但不含只是切到背景的 `TRIM_MEMORY_UI_HIDDEN`；或 `onLowMemory`）時，`WayMap` 會自動呼叫 `MapView.onLowMemory()` 釋放快取。

## 建置

需要 Android SDK（compileSdk 36）與 **JDK 17–21**（Gradle 8.14 無法在 JDK 25 上執行）。

- `./gradlew :geo:test :waymap:testDebugUnitTest` — 單元測試
- `./gradlew publishToMavenLocal` — 發佈到本機 `~/.m2`（版本取 `gradle.properties` 的 `version`）

使用端可用 Gradle composite build（`includeBuild("../WayMap")`）直接引用本機原始碼同步開發，不必先打 tag。

## 發版

打 tag（例如 `v0.1.0`）並 push 後，JitPack 會在第一次被請求時建置；建置紀錄：
`https://jitpack.io/com/github/ShiKi5473/WayMap/<tag>/build.log`
