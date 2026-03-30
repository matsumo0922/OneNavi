# 09. ETC Card Detection via Android Auto / AAOS

## Overview

Android Auto のステータスバーに ETC アイコンが表示されることから、車両の ETC カード挿入状態はヘッドユニット経由で取得可能であることが判明。本ドキュメントでは、サードパーティアプリから ETC カード状態を取得する方法と制約を整理する。

---

## 1. API 概要

### Car App Library (`androidx.car.app`) — サードパーティアプリ向け

`androidx.car.app.hardware.info.TollCard` クラスを使用してリスナーベースで取得する。Car App API Level 3 以上で利用可能。Android Auto・Android Automotive OS 両対応。

#### 関連クラス

| クラス | 役割 |
|---|---|
| `CarHardwareManager` | Car Hardware API のエントリポイント |
| `CarInfo` | `addTollListener()` / `removeTollListener()` を提供 |
| `TollCard` | ETC カードの状態とタイプを保持するデータクラス |

#### 使用例

```kotlin
val carHardwareManager = carContext.getCarService(CarHardwareManager::class.java)
val carInfo = carHardwareManager.carInfo

val listener = OnCarDataAvailableListener<TollCard> { data ->
    if (data.tollCardState.status == CarValue.STATUS_SUCCESS) {
        val state = data.tollCardState.value  // カード状態
        val type = data.tollCardType.value     // カードタイプ
    }
}

carInfo.addTollListener(carContext.mainExecutor, listener)

// 解除
carInfo.removeTollListener(listener)
```

### Android Car API (`android.car`) — AAOS ネイティブアプリ向け

Android Automotive OS のシステムレベル API。`CarPropertyManager` 経由で以下のプロパティを取得できる。

| VehiclePropertyId | 内容 |
|---|---|
| `ELECTRONIC_TOLL_COLLECTION_CARD_STATUS` | カード挿入状態 |
| `ELECTRONIC_TOLL_COLLECTION_CARD_TYPE` | カードタイプ |

---

## 2. ステータス値

### カード状態 (`TollCardState` / `VehicleElectronicTollCollectionCardStatus`)

| 値 | 意味 |
|---|---|
| `UNKNOWN` (0) | 不明 |
| `VALID` (1) | カード挿入済み・有効 |
| `INVALID` (2) | カードあり・期限切れ等で無効 |
| `NOT_INSERTED` (3) | カード未挿入 |

### カードタイプ (`TollCardType` / `VehicleElectronicTollCollectionCardType`)

| 値 | 意味 |
|---|---|
| `UNKNOWN` (0) | 不明 |
| `JP_ELECTRONIC_TOLL_COLLECTION_CARD` (1) | 日本 ETC カード |
| `JP_ELECTRONIC_TOLL_COLLECTION_CARD_V2` (2) | 日本 ETC 2.0 カード |

> **Note:** API で定義されているカードタイプは日本の ETC のみ。他国の ETC システムは未定義。

---

## 3. 必要なパーミッション

| 環境 | パーミッション |
|---|---|
| Android Auto | `com.google.android.gms.permission.CAR_FUEL` |
| Android Automotive OS | `android.car.permission.CAR_INFO` |

---

## 4. ステータスバーの ETC アイコン

Android Auto のステータスバー（時計の横）に表示される ETC アイコンは**システム UI が描画**している。この仕組みは以下の通り:

1. 車両の OBU (On-Board Unit) が ETC カードリーダーと通信
2. ヘッドユニットの Vehicle HAL (VHAL) が CAN バス経由でカード状態を読み取る
3. VHAL が `ELECTRONIC_TOLL_COLLECTION_CARD_STATUS` プロパティとして公開
4. Android Auto のシステム UI がプロパティを監視し、有効なカード検出時にアイコンを表示

**サードパーティアプリからステータスバーにアイコンを追加することはできない。**

---

## 5. 制約・注意事項

- **ヘッドユニットの VHAL 実装依存**: OEM が ETC 関連プロパティを実装していない場合、`CarValue.STATUS_UNIMPLEMENTED` が返される
- **社外ヘッドユニット（Pioneer / Kenwood / Alpine 等）**: CAN バスとの連携がないことが多く、ETC 状態を取得できない可能性が高い
- **純正 AAOS 搭載車**: トヨタ・ホンダ・日産等の日本メーカーの純正システムが最も対応している可能性が高い
- **Android Auto (phone-projected mode)**: データはヘッドユニットから Android Auto プロトコル経由で取得されるため、ヘッドユニット側の VHAL サポートに依存する

---

## 6. OneNavi での活用方針

| 用途 | 実現可能性 |
|---|---|
| ETC カード挿入状態の表示 | Car App API Level 3+ で可能（VHAL 実装依存） |
| ETC 2.0 判定 | 同上 |
| 有料道路の料金表示と連携 | カード状態の取得は可能。料金計算は別途 Google Routes API 等が必要 |
| ステータスバーへのアイコン表示 | 不可（システム UI のみ） |

---

## 7. 参考リンク

- [TollCard - Android Developers](https://developer.android.com/reference/androidx/car/app/hardware/info/TollCard)
- [CarInfo - Android Developers](https://developer.android.com/reference/androidx/car/app/hardware/info/CarInfo)
- [CarHardwareManager - Android Developers](https://developer.android.com/reference/androidx/car/app/hardware/CarHardwareManager)
- [VehicleElectronicTollCollectionCardStatus - Android Developers](https://developer.android.com/reference/android/car/hardware/property/VehicleElectronicTollCollectionCardStatus)
- [VehicleElectronicTollCollectionCardType - Android Developers](https://developer.android.com/reference/android/car/hardware/property/VehicleElectronicTollCollectionCardType)
- [Car Hardware APIs - Android Developers](https://developer.android.com/training/cars/apps/library/car-hardware-api)
- [Status Bar - Design for Driving](https://developers.google.com/cars/design/android-auto/product-experience/system-ui/status-bar)
