# 14. Android Auto 上の Google Maps UI 調査

> **作成日:** 2026-04-04  
> **ステータス:** 調査メモ / 設計判断用  
> **対象:** Android Auto 上で動作する Google Maps の UI が、公開されている Car App Template の制約を超えて見える理由の調査

---

## 1. 結論

**今回観測した Google Maps の UI は、必ずしも「Template の制約を破っている」わけではない。**  
見た目が非常にリッチなのは、

- Android Auto の **Template が担当する部分**
- ナビアプリ自身が `Surface` に **自前描画する地図レイヤー**

が明確に分かれており、Google Maps が後者をかなり高い完成度で実装しているためである。

特に重要なのは、Android for Cars App Library ではナビアプリが `NavigationTemplate` などを使う一方で、**地図そのものは app-owned の `Surface` に描画できる**点である。  
つまり、以下は Template の限界に直接縛られない。

- 3D 地図
- ベクターレンダリング
- カメラのチルト、追従、俯瞰切り替え
- 交通情報オーバーレイ
- 代替ルート線の描画
- マーカー、ルート強調、走行済み部分の表現
- 建物や道路スタイルのリッチさ

一方で、以下は主に host と template が担当する。

- 画面上部の案内バナー
- ETA / 残距離 / 残時間カード
- 一部のアクションストリップ
- 検索、リスト、設定などの補助 UI

したがって、スクリーンショットから受ける「Template の枠を超えた独自 UI」に見える印象の多くは、実際には

1. **Template が載せる UI**
2. **アプリが `Surface` に描いている地図**

の合成結果で説明できる。

---

## 2. なぜ「やけにリッチ」に見えるのか

Android Auto のナビアプリは、通常の Android アプリのように画面全体を自由レイアウトするわけではない。  
その代わりに、ホストが安全性を担保する Template ベース UI を提供する。

ここで重要なのは、**地図は host が描くとは限らない**ことだ。

公式 API では、`AppManager.setSurfaceCallback()` を通じてアプリが描画先 `Surface` を受け取れる。  
この `Surface` はナビアプリが独自コンテンツを描くためのものであり、公式ドキュメント上も「navigation app の map を描く用途」に使うものとして説明されている。

そのため Google Maps は、おそらく次のような分担で実装している。

- **Host / Template 側**
  - 上部のターン案内
  - 進行中のナビ情報カード
  - 検索や設定の枠組み
  - 運転中 UI 制約、タップ対象、表示ルール
- **Google Maps アプリ側**
  - 地図レンダラ本体
  - 3D 建物
  - 交通表示
  - 現在地矢印
  - ルートライン
  - 代替ルート線
  - POI マーカー
  - カメラ演出
  - 一部のタップ処理

見た目の豪華さはほぼ後者に由来する。  
Template を破っているのではなく、**Template の下にある map surface を徹底的に作り込んでいる**と理解するのが正しい。

---

## 3. 公開 API で分かる Android Auto ナビアプリの実装モデル

### 3.1 基本構成

公開 API から読み取れる実装モデルは、概ね次の通りである。

1. `CarAppService` を提供する
2. `androidx.car.app.category.NAVIGATION` としてホストに接続する
3. `NavigationTemplate` などのナビ向け template を返す
4. `AppManager.setSurfaceCallback()` で `Surface` を受け取る
5. その `Surface` に自前の地図を描く
6. `NavigationManager` に `Trip` / `Step` を流して host に案内 UI を表示させる

このモデルに従う限り、アプリは「自由レイアウト」はできないが、**地図の見た目と動きはかなり自由度が高い**。

### 3.2 `NavigationTemplate` は地図を直接描く API ではない

`NavigationTemplate` の公式リファレンスには、テンプレート自体は描画 `Surface` を露出せず、描画には `setSurfaceCallback` を使うように明記されている。  
つまり `NavigationTemplate` は「地図を含むフレームワーク」ではあるが、**地図レンダラそのものではない**。

この構造があるため、Google Maps のようなアプリは host 標準のナビ UI に加えて、自前の高品質な地図描画を組み合わせられる。

### 3.3 `Surface` への描画はかなり本格的にできる

公式の「Draw maps」ドキュメントでは、`SurfaceContainer` から渡された `Surface` を使って描画する方法が説明されている。  
さらに、そのページでは **`VirtualDisplay` と `Presentation` を使って既存の `View` 階層を仮想ディスプレイへレンダリングする実装例** まで示されている。

これは非常に重要である。

このサンプルが意味するのは、アプリが必ずしも低レベルな `Canvas` 描画だけで車載地図を作る必要はなく、既存の地図レンダラや `View` ベース UI をかなり再利用できる、ということだ。  
Google Maps のように既存の地図描画スタックが成熟しているアプリなら、**車載用に一から別の地図エンジンを書く必要はない**可能性が高い。

### 3.4 地図操作も公開 API で扱える

`SurfaceCallback` は以下のイベントを提供する。

- `onClick`
- `onScroll`
- `onScale`
- `onFling`
- `onVisibleAreaChanged`
- `onStableAreaChanged`
- `onSurfaceAvailable`
- `onSurfaceDestroyed`

このため、パン、ズーム、フォーカス移動、可視領域に応じたカメラ制御なども公開 API で成立する。  
スクリーンショットに見えるズーム UI や地図の追従挙動がリッチであっても、それだけでは private API の証拠にはならない。

### 3.5 ナビ情報は `NavigationManager` に流す

`CarContext` の説明では、turn-by-turn navigation app がホストとナビ情報をやり取りするための `NavigationManager` が提供されることが明記されている。  
また、テスト用 `TestNavigationManager` の記述からも、実アプリが `updateTrip()` を通じて `Trip` を送り続ける前提であることが分かる。

つまり、Google Maps の実装は概念的には

- 地図: app-rendered
- ターン案内メタデータ: host-facing

という 2 系統に分かれていると考えるのが自然である。

---

## 4. スクリーンショットごとの解釈

以下では、提示された 5 枚の画面を公開 API の観点から読み解く。

### 4.1 画像 1: 代替ルート一覧 + 地図

見えているもの:

- 左側に map surface
- 右側に「別の経路」リスト
- ルート A/B の選択 UI
- 上部にターン案内バナー

これは旧 API であれば `RoutePreviewNavigationTemplate` でかなり素直に説明できる。  
この template は「カスタム描画の map と route list を並べる」ためのものとして定義されている。

ただし App Library 1.7.0 では `RoutePreviewNavigationTemplate` は deprecated で、`MapWithContentTemplate` への移行が推奨されている。  
したがって現在の Google Maps 相当の実装を公開 API ベースで考えるなら、

- `MapWithContentTemplate`
- `ListTemplate`
- app-rendered map surface

の組み合わせで理解するのが妥当である。

### 4.2 画像 2: 3D 地図でのナビ中画面

見えているもの:

- 強いチルトが入った 3D 地図
- 建物や道路の立体的な描写
- 交通状況らしきルート上の表現
- 上部の大きな案内バナー
- 下部の残時間 / 距離 / ETA カード

このうち「リッチさ」を生んでいる本体は map surface 側である。  
`NavigationTemplate` 自体は地図の質感や 3D 表現を規定しない。  
アプリが `Surface` に何を描くか次第なので、Google Maps の 3D レンダラがそのまま見えていると考えるのが自然である。

一方、上部バナーや下部カードは host と `Trip` 更新で成立する部分であり、こちらは Android Auto の公開ナビ構造と整合する。

### 4.3 画像 3: ルート沿い検索のガソリンスタンド一覧

見えているもの:

- 右側に POI リスト
- 地図上に連番マーカー
- ナビ継続中の上部バナー
- 進行ルートを残したままの沿道検索

これは非常に「Google Maps らしい」画面だが、公開 API の範囲でも十分説明できる。

- リスト: `PlaceListNavigationTemplate` 旧 API、または `MapWithContentTemplate` + `ListTemplate`
- 地図マーカー: app-rendered surface 側
- ルート線保持: app-rendered surface 側
- ナビ継続中の案内メタデータ: `NavigationManager`

要するに、**map surface を持ったまま content template を差し替える**ことで作れる UI である。

### 4.4 画像 4: 検索バー + カテゴリ一覧

見えているもの:

- 検索バー
- 音声入力ボタン
- 最近 / カテゴリ / 保存済み
- カテゴリ一覧

これは `SearchTemplate` を中心とした実装で説明しやすい。  
`SearchTemplate` はテキスト検索と検索結果リストを扱うための template であり、検索中に結果をインタラクティブに更新できるよう、refresh 制約も緩い。

Google Maps はこれに加えて、自社の検索履歴、カテゴリ、保存済み地点などのデータを流し込んでいると考えられる。

### 4.5 画像 5: ナビ中設定パネル

見えているもの:

- ナビ継続中の map surface
- 右側に設定パネル
- 音声、交通、航空写真、3D 建物、アラートの切り替え

これも `MapWithContentTemplate` 的な発想で説明できる。  
`MapWithContentTemplate` は map を見せたまま content template を重ねる方向の API であり、旧来の `MapTemplate` / `PlaceListNavigationTemplate` / `RoutePreviewNavigationTemplate` を集約する位置づけにある。

設定パネルのトグル UI 自体は host 制約の範囲に入るが、背景で見えている地図の豪華さは引き続き app-rendered surface 側である。

---

## 5. Google Maps が「公開 API だけではないかもしれない」部分

ここは事実と推測を明確に分ける。

### 5.1 事実として言えること

以下は公開 API だけで十分説明できる。

- 高品質な地図描画
- 3D 建物
- 代替ルート表示
- POI マーカー
- ルート沿い検索 UI
- ナビ継続中の設定パネル
- 検索中のリスト更新
- パン / ズーム / スクロール

したがって、「リッチだから private API に違いない」とは言えない。

### 5.2 推測としてはあり得ること

ただし Google Maps は Google 製アプリであり、次のような追加優位を持つ可能性は高い。

- host 側とのより深い統合
- Google Assistant との密な連携
- 検索、交通、インシデント、営業状況などの内部データ結合
- 遷移アニメーションや細かい表示制御の最適化
- Android Auto / AAOS の新機能への先行対応

しかし、**公開資料の範囲では「この画面は private API がないと実現できない」と断定する根拠は見つからなかった。**

今回の調査で確実に言えるのは、スクリーンショットの印象を生んでいる主要因は

- 非公開特権そのもの

ではなく、

- 公開 API で許されている map surface 描画を Google Maps が非常に高品質に実装していること

である、という点である。

---

## 6. OneNavi の現状実装との差分

> 2026-04-16 更新:
> 旧 provider 削除後に `NavigationTemplate` だけを返す状態では Android Auto ナビとして不完全だったため、
> 現在の head では `CarAppService` の manifest 公開を外している。
> 以下は調査当時の実装メモであり、現行ビルドで Android Auto が有効という意味ではない。

このリポジトリには Android Auto 向けの最小構成がすでに存在する。

### 6.1 現在あるもの

- `CarAppService`
- `Session`
- `NavigationTemplate`
- `Action.PAN`
- 旧 provider の Android Auto 拡張
- map surface への style 適用
- 現在地 puck の表示

該当コード:

- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarAppService.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarSession.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarMapScreen.kt`
- `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarMapObserver.kt`

### 6.2 現在の実装が最小構成であること

`OneNaviCarMapScreen` は `NavigationTemplate.Builder()` を返し、ActionStrip と `Action.PAN` だけを設定している。  
現時点ではターン案内、トリップカード、代替ルート、検索、設定パネルなどは未実装である。

また `OneNaviCarMapObserver` は、旧 provider の style 読み込みと現在地 puck 設定のみを行っている。
ルートライン、交通表示、POI オーバーレイ、カメラモード、`NavigationManager.updateTrip()` 連携はまだ入っていない。

つまり OneNavi は「Android Auto ナビの器」はできているが、Google Maps が見せているようなリッチさを構成する要素はほぼ未着手である。

### 6.3 権限まわりの確認ポイント

調査当時の manifest では `androidx.car.app.NAVIGATION_TEMPLATES` は宣言されていたが、**公式リファレンス上 `setSurfaceCallback()` には `androidx.car.app.ACCESS_SURFACE` の宣言が必要**である。

もし今後 OneNavi 側で独自の map surface 制御を明示的に進めるなら、以下は再確認すべきである。

- `ACCESS_SURFACE` を manifest に宣言しているか
- 旧 provider の Android Auto 拡張が内部で要求する条件を満たしているか
- 現在の実装がホスト上で問題なく `Surface` を取得できているか

この点は「Google Maps は特別」という話ではなく、**OneNavi が公開 API 上の基本要件を満たしているかどうかの確認事項**である。

---

## 7. OneNavi で Google Maps に近い体験を作るには何が必要か

### 7.1 必須要素

Google Maps に近い体験を作るには、少なくとも次が必要である。

- `NavigationTemplate` 上での turn-by-turn 更新
- `NavigationManager.updateTrip()` による案内情報連携
- map surface へのルートライン描画
- 代替ルートの重ね描き
- Follow / Overview / Pan のカメラ状態管理
- traffic layer の反映
- 沿道検索時に route を保ったまま place list を出すフロー
- 設定や絞り込みを `MapWithContentTemplate` 系で整理する構成

### 7.2 実装上の意味

重要なのは、「豪華な Android Auto UI を作る」のではなく、

- **地図 surface 側の表現力を上げる**
- **host に渡すナビメタデータを正しく更新する**
- **list / search / settings を map と共存させる**

という 3 系統を揃えることである。

Google Maps との差は、Template の自由度ではなく、

- 地図レンダラの完成度
- ナビ状態管理
- 検索 / route preview / along-route browse のフロー設計

にある。

---

## 8. 最終判断

今回の調査からの最終判断は以下。

### 8.1 誤解しやすい点

「Google Maps の Android Auto UI は template の域を超えている」という直感は半分正しく、半分誤りである。

- **正しい部分**  
  見た目は確かに template 単体の印象を超えている。

- **誤っている部分**  
  それは template を破っているからではなく、template と app-rendered map surface を高度に組み合わせているからである。

### 8.2 技術的な本質

技術的な本質は次の一文に尽きる。

> Android Auto のナビアプリは、Template によって安全な UI 枠を host に任せつつ、地図そのものは `Surface` に自前描画できる。Google Maps の「リッチさ」はこの自前描画領域を最大限活用していることに由来する。

### 8.3 OneNavi への示唆

OneNavi にとって重要なのは、「Google Maps だけができる特権 UI を追う」ことではない。  
まずは公開 API の範囲で、

- `Surface` 上の地図品質
- `NavigationManager` 連携
- `MapWithContentTemplate` を軸にした route preview / search / settings

を正しく積み上げることが、最短で Google Maps に近い体験へ寄せる道筋である。

---

## 9. 既存 Compose Navigation UI を Android Auto Surface へ載せる方針

> **追記日:** 2026-06-04
> **背景:** 既存の `MapNavigationContent` / `MapNavigationManeuverPanel` などの Compose UI を、
> Android Auto 側でも作り直さずに共通化したいという要望。PlayStore リリース予定はないため、
> 配布ポリシー由来の制約は考慮しない前提で検討する。

### 9.1 結論

**Compose の navigation overlay は、作り直さず Android Auto Surface に載せて共通化できる見込みがある。**
ただし「地図ごと丸ごと」載せるのは現状の地図レンダラ（Google Maps SDK）の制約で難しく、
**地図レイヤーと Compose overlay レイヤーを分離**したうえで overlay 側だけを共通化するのが現実的である。

### 9.2 共通化の前提はすでに満たされている

`MapNavigationContent` と `MapNavigationManeuverPanel` は完全に state hoisting されており、
内部で `koinViewModel()` 等に依存していない。引数で state を受け取るだけである。

```kotlin
internal fun MapNavigationContent(
    guidanceState: GuidanceState,
    overlayState: MapOverlayState,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
)
```

したがって、同じ state を Android Auto 側から渡せば同じ Composable がそのまま動作する。
phone / car 双方が呼ぶ共通エントリ Composable を切り出すのが望ましい。

```kotlin
@Composable
internal fun OneNaviNavigationOverlay(
    guidanceState: GuidanceState,
    overlayState: MapOverlayState,
    modifier: Modifier = Modifier,
)
```

state は ViewModel に閉じ込めず、`GuidanceManager` / repository 層（Koin singleton）から
phone と car の双方が購読する形にすることで、画面間で共有できる。
`MapViewModel` に閉じている state があれば、その分だけ下層へ引き上げる必要がある。

### 9.3 描画ルート（VirtualDisplay + Presentation + ComposeView）

`§3.3` で触れた `VirtualDisplay` / `Presentation` 手法を実運用する形。

```text
AppManager.setSurfaceCallback() で受け取る Surface
  -> DisplayManager.createVirtualDisplay(name, width, height, dpi, surface, flags)
  -> Presentation(carContext, virtualDisplay.display)
  -> setContentView(composeView)
  -> ComposeView { OneNaviNavigationOverlay(state) }
```

### 9.4 実装上のハマりどころ

1. **Compose を View ツリー外で動かすための owner 注入**
   `ComposeView` は `ViewTreeLifecycleOwner` / `ViewTreeViewModelStoreOwner` /
   `ViewTreeSavedStateRegistryOwner` を要求する。`Presentation` の decorView には自動で
   付かないため、`Session` の `Lifecycle` をぶら下げた自前 owner を 3 点セットで注入する。
   これが無いと初回 compose で即クラッシュする。

2. **タッチ転送**
   Compose は `MotionEvent` 駆動だが、host からは `SurfaceCallback.onClick` / `onScroll` /
   `onFling` しか届かない。これらを `MotionEvent` へ組み立て直して
   `composeView.dispatchTouchEvent()` へ流す変換層が要る。ボタンタップ程度なら
   `onClick(x, y)` から DOWN/UP を合成すれば足りる。

3. **地図レンダラ（Google Maps SDK）の Surface 制約**
   現状の地図は `play-services-maps` ベースであり、`MapView` は内部 `SurfaceView` / GL で
   任意の `Surface` や `VirtualDisplay` 上に描画できない設計である。
   そのため `MapNavigationContent` を**地図ごと**単一 `ComposeView` に載せる構成は成立しにくい。
   地図は Android Auto 用に別経路で描画し、その上に**透過 `ComposeView`** で overlay
   （`ManeuverPanel` / `EtaCard` / `AlternativesCard` 等）を重ねる二層構成にする。
   `ManeuverPanel` 自体は地図非依存なので、overlay 側の共通化は素直に成立する。

4. **サイズ / DPI / visibleArea**
   `onSurfaceAvailable` / `onVisibleAreaChanged` で得る幅・高さ・dpi を `VirtualDisplay` と
   Compose の `Density` に正しく渡す。host の safe area を `WindowInsets` 相当で overlay に
   伝えないと、ETA カード等がクラスター領域に食われる。

### 9.5 PoC 推奨順序

1. `OneNaviNavigationOverlay(state)` を切り出し、phone 側を差し替えてリグレッションが無いことを確認する。
2. 透過 `ComposeView` を `VirtualDisplay` + `Presentation` で Android Auto Surface に出す最小 PoC
   （まず固定文字列）で owner 注入を検証する。
3. その上に `ManeuverPanel` だけを仮 state で載せる。
4. `GuidanceManager` の state を car session に配線する。
5. 地図レイヤーを別途差し込む（Google Maps SDK の Surface 制約への対処は別途判断）。

### 9.6 Maneuver をリッチに描く観点での意味

この方針が成立すれば、Maneuver 描画は host 固定アイコンに縛られず、phone と同一の
自前グリフ（`MapGuidanceManeuverArrow` の Canvas 描画、`MapNavigationManeuverLaneIcons` の
レーン図）を Android Auto でもそのまま使える。`§7` の「地図 surface 側の表現力を上げる」を、
UI コードを二重に持たずに達成する道筋になる。

---

## 10. 参考ソース

### Android Developers / AndroidX 公式

1. Draw maps for apps using the Android for Cars App Library  
   https://developer.android.com/training/cars/apps/library/draw-maps

2. Let users interact with your map  
   https://developer.android.com/training/cars/apps/library/interact-map

3. Build navigation apps for cars  
   https://developer.android.com/training/cars/apps/navigation

4. `AppManager` API reference  
   https://developer.android.com/reference/androidx/car/app/AppManager

5. `CarAppPermission` API reference  
   https://developer.android.com/reference/androidx/car/app/CarAppPermission

6. `SurfaceCallback` API reference  
   https://developer.android.com/reference/androidx/car/app/SurfaceCallback

7. `SurfaceContainer` API reference  
   https://developer.android.com/reference/androidx/car/app/SurfaceContainer

8. `CarContext` API reference  
   https://developer.android.com/reference/androidx/car/app/CarContext

9. `NavigationTemplate` API reference  
   https://developer.android.com/reference/androidx/car/app/navigation/model/NavigationTemplate

10. `MapWithContentTemplate` API reference  
    https://developer.android.com/reference/androidx/car/app/navigation/model/MapWithContentTemplate

11. `SearchTemplate` API reference  
    https://developer.android.com/reference/kotlin/androidx/car/app/model/SearchTemplate

12. `RoutePreviewNavigationTemplate` API reference  
    https://developer.android.com/reference/kotlin/androidx/car/app/navigation/model/RoutePreviewNavigationTemplate

13. `PlaceListNavigationTemplate` API reference  
    https://developer.android.com/reference/kotlin/androidx/car/app/navigation/model/PlaceListNavigationTemplate

14. `MapTemplate` API reference  
    https://developer.android.com/reference/kotlin/androidx/car/app/navigation/model/MapTemplate

15. `Step.Builder` API reference  
    https://developer.android.com/reference/androidx/car/app/navigation/model/Step.Builder

16. `TestNavigationManager` API reference  
    https://developer.android.com/reference/kotlin/androidx/car/app/testing/navigation/TestNavigationManager

17. `DisplayManager.createVirtualDisplay` API reference  
    https://developer.android.com/reference/android/hardware/display/DisplayManager

18. `Presentation` API reference  
    https://developer.android.com/reference/android/app/Presentation

19. `ComposeView` API reference  
    https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/ComposeView

### リポジトリ内確認箇所

1. `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarAppService.kt`
2. `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarSession.kt`
3. `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarMapScreen.kt`
4. `composeApp/src/androidMain/kotlin/me/matsumo/onenavi/car/OneNaviCarMapObserver.kt`
5. `composeApp/src/androidMain/AndroidManifest.xml`
6. `gradle/libs.versions.toml`
