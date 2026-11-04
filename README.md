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
- `lines`：折線，可加白色 casing 底線。
- `pointLayers`：大量靜態點（GeoJSON source + SymbolLayer，數千點仍流暢），畫在 lines 之上、markers 之下。
- `userLocation`：`HIDDEN` / `SHOW`（首次定位置中一次，不接管鏡頭）/ `FOLLOW` / `FOLLOW_BEARING`（行車用，隨車頭旋轉）。
- 預設底圖為免金鑰的 OpenFreeMap Liberty；`styleUrl` 可替換。
- 地圖顯示時請保持 `WayMap` 在組合樹中（其他畫面疊在上方），避免定位元件尚有未完成的定位時被銷毀而閃退。

## 建置

需要 Android SDK（compileSdk 36）與 **JDK 17–21**（Gradle 8.14 無法在 JDK 25 上執行）。

- `./gradlew :geo:test :waymap:testDebugUnitTest` — 單元測試
- `./gradlew publishToMavenLocal` — 發佈到本機 `~/.m2`（版本取 `gradle.properties` 的 `version`）

使用端可用 Gradle composite build（`includeBuild("../WayMap")`）直接引用本機原始碼同步開發，不必先打 tag。

## 發版

打 tag（例如 `v0.1.0`）並 push 後，JitPack 會在第一次被請求時建置；建置紀錄：
`https://jitpack.io/com/github/ShiKi5473/WayMap/<tag>/build.log`
