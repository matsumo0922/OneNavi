# 23. Route Compare Dev Tool — 外部ルートを Google Routes API で再現する waypoint 選定ロジック

## 0. このドキュメントの目的

`dev-tools/route-compare/` に作った Vite + TS のデバッグ用 mini app と、その中で
試行錯誤の末に確立した「外部API ライブラリのルートを Google Routes API
(`/v2:computeRoutes`) で polyline レベルで再現するための waypoint 選定ロジック」
を、context を失った状態からでも再開・拡張できる詳細さで記録する。

実装は現状すべて TypeScript で `dev-tools/route-compare/src/` に存在し、本番
モジュール (`composeApp`, `core/*`, `feature/*`) には未統合。本デバッグツールで
得られた知見を最終的に Android 側 (Kotlin) の Navigation 統合層に落とし込む
ための「正本となるアルゴリズム仕様」がこのドキュメント。

### 0.1 決定された運用設定 (Android 統合で採用)

dev tool 上での試行錯誤の結果、shakuji-tsukuba (74km / 一般道 + 高速 + 市内) で
inflation ≒ 1.00x (= 外部ルートとほぼ完全一致) を達成する設定として、以下を
**Android 本番統合での採用方針** として確定する:

| 設定項目 | 値 | 根拠 |
|---|---|---|
| 案内地点 (maneuver) | **None** | 案内地点ピンポイントは方向情報を持たず、Routes API がそこで方向を間違えるため |
| 案内地点外 (between) | **Every 4th** (= targetGap 4km) | 25 chunk 上限以内に収め、形状追従に十分な密度 |
| Chunk size | **25** | Routes API v2 intermediate ハードキャップ |
| Set heading | **ON** | polyline 接線方向で snap 先を絞る |
| Use via waypoints | **ON** | intermediates を pass-through 扱いにし、停車不可な高速本線も選択肢に入れる |

**この設定下では、proto guide.json の解析 (§3-§4) は Kotlin 側では不要** で、
外部API ライブラリが返す polyline (= LineString) と origin/destination
だけあれば再現できる。詳細は §11.2 参照。

§3-§10 の解析 (案内地点判定 / 構造点除外) は dev tool 上で代替方針との比較
検証のために残す。「将来 maneuver を併用したくなった場合に再び拾えるように」
という保険として本ドキュメントにも全量残す。

背景の前提知識:

- spec/18 (外部API 移行計画), spec/20 (NavigationView 反射注入調査),
  spec/21 (外部API proto / announcement 仕様), spec/22 (route token / Custom
  Navigator 評価) の通読を推奨。本ドキュメントは spec/22 §11 の実機 curl 検証
  で出た「形状追従できない」結論を **waypoint 選定戦略を改善することで一定
  程度克服できる** ことを示し、その手順を残す。
- **外部API 提供元の API 提供事業者名・製品名は本ドキュメント上に出さない**
  (CLAUDE.md §厳命)。本ドキュメントでは「外部API ライブラリ」「外部
  ルート」「外部API 提供元」の代替表記のみ使用する。

---

## 1. 全体構成

### 1.1 dev-tools/route-compare の役割

「外部API ライブラリが返したルート」と「Google Routes API v2 を chunk 分割
して投げ直したときに返るルート」を 1 枚の地図上に並べ、polyline がどれだけ
一致するかを目視 + 数値で確認するためのツール。

入力には 2 種類のモードがある:

#### Sample mode

`ext-api/analysis/sample/<sample-id>/extracted/` 以下の生データ:

| ファイル | 用途 |
|---|---|
| `route/route-polyline.geojson` | 外部ルートの polyline (LineString)。可視化用 |
| `guide/guide-points.json` | 案内 GP 列。緯度経度・累積距離・place_name 等のフラット表現 |
| `guide/guide.json` | proto を JSON にダンプした正本。GP の flags / label と発話 block を含む |

#### Custom mode (任意の地点で検証)

サンプルデータを使わず、地図クリックで origin / 経由地 / destination を指定する
モード (§8.5)。Fetch すると 2 段階で動く:

1. ユーザ click 点で Routes API を 1 度叩いて reference polyline を取得 (= 外部
   ルート役)
2. その polyline 上で本アルゴリズムを走らせて test polyline を取得し、reference
   と並べて比較

サンプルが存在しない経路や検証したい特定地域 (高架/側道が密集する都市部等) を
ピンポイントでテストできる。

#### 出力 layer

- 外部ルート polyline (青、5px) — sample mode は外部 raw、custom mode は Step 1 の
  reference polyline
- Routes API split polyline (chunk ごとに色相をずらした赤系、4px) — Step 2 の test
- Sent waypoints (緑/紫/赤の番号バッジ、chunk 境界は黒太枠)
- Custom inputs (teal の点線ボーダー番号バッジ、custom mode のみ) — ユーザ click 点

UI で sampling rate / chunk size / heading 設定 / via 設定を変えながら、API
呼び出しと再描画を繰り返して試行できる。

### 1.2 ファイル構成

```
dev-tools/route-compare/
├── package.json / tsconfig.json / vite.config.ts / .env.example
├── index.html
└── src/
    ├── main.ts        # entry / イベント配線 / waypoint ビルド / FPS sampling
    ├── map.ts         # Google Maps init, layer 描画, レイヤー toggle
    ├── samples.ts     # 生データ load (geojson + guide-points), datum 変換
    ├── maneuvers.ts   # 案内地点 / 構造点判定 (proto 解析)
    ├── routes-api.ts  # Routes API v2 chunk 分割呼び出し + polyline decode
    ├── polyline.ts    # encoded polyline デコーダ
    ├── datum.ts       # Tokyo Datum → WGS84 (Hosoi 簡易式)
    ├── style.css
    └── vite-env.d.ts
```

`Makefile` に以下のターゲットを追加済み:

```
make route-compare-setup   # npm install + .env 雛形作成
make route-compare-dev     # vite dev server (port 5174)
make route-compare         # setup + dev を順に
```

`.env` には `VITE_GOOGLE_API_KEY=<key>` を入れる。Routes API + Maps JS API の
両方が叩ける必要があるので Routes / Maps JS / Places (Optional) を有効化した
プロジェクトのキーを使う。

Vite 設定で `server.fs.allow` をリポジトリルートに広げ、`/@fs/<absolute path>`
で `ext-api/analysis/sample/...` を直接 fetch している。これにより
sample データを repo 外にコピーせず読み込める (`samples.ts` 参照)。

---

## 2. 座標系の取り扱い (Tokyo Datum → WGS84)

### 2.1 問題

`ext-api/analysis/sample/<id>/raw/mocha-route.json` の `unit.datum`
は `"tokyo"` で記録されている。サンプルキャプチャは `x-up-datum: wgs84` ヘッダ
が無い時代に取得されたため、座標は **旧日本測地系 (Tokyo Datum)** のまま。

Google Maps は WGS84 で描画するため、変換せずに緯度経度を載せると polyline が
**北西方向に約 400m ずれる** (Tokyo → WGS84 で日本国内では概ね lat +0.003°,
lng -0.003° になる)。

なお実装側 `ext-api/src/main/kotlin/.../core/net/
ExtApiHeaders.kt` は `x-up-datum: wgs84` を全リクエストに付与済みなので、
**ライブラリ経由で取得した route は WGS84**、ライブラリ未経由の analysis サンプル
だけが Tokyo Datum、という二重状態になっている点に注意。

### 2.2 採用した変換式

Hosoi 簡易式 (`src/datum.ts`):

```ts
function tokyoToWgs84({ lat, lng }: LatLng): LatLng {
  return {
    lat: lat - 0.00010695 * lat + 0.000017464 * lng + 0.0046017,
    lng: lng - 0.000046038 * lat - 0.000083043 * lng + 0.010040,
  };
}
```

国土地理院水準原点 (永田町) の例で誤差約 2m に収まることを実機で確認。
公式 TKY2JGD 相当の精度は不要で、デバッグ用途には十分。

### 2.3 適用箇所

`src/samples.ts`:

- `loadExternalPolyline(sampleId)`: geojson の coordinates をそのまま swap
  して `{lat, lng}` にした上で `tokyoToWgs84Path` を通す
- `loadGuidePoints(sampleId)`: GP の `lat_e6 / lon_e6` を `÷ 3,600,000` で度に
  変換 (NOT `÷ 1e6`、フィールド名は misleading だが arc-millisecond スケール
  なので `3.6e6` で割る) → そのまま `tokyoToWgs84` 適用

Routes API レスポンスの polyline は WGS84 なので変換しない。

---

## 3. 案内地点 (Maneuver) の判定 — `src/maneuvers.ts`

### 3.1 用語整理

外部API が返す GP (= guide point / 案内地点) には大別して 2 種ある:

- **音声 maneuver 付き GP**: 「およそ 500m 先三軒寺左方向です」のような
  announcement block が結びついている。`block.announcements[].priority` が
  template_id を表し、physical maneuver template (100, 104, 105, 314, 303, 304,
  404, 422) のいずれかが立っている (spec/21 §3 / spec/22 §11.3)。
- **構造 maneuver 付き GP**: 音声は出ないが proto レベルで「分岐」「合流」「ランプ
  遷移」「料金所」「JCT」等の構造マーカーが立っている。`flags.field_1` が立つもの
  (= JCT 等の主要 maneuver) と、`flags.field_3.field_1 ∈ {1, 7, 10}` で表される
  もの (= 1: 分岐 / 7: 合流 / 10: 高速ランプ系) の 2 系統。

両方を OR で集めたものが「実際に進路選択が発生する地点」 = 案内地点。

### 3.2 PHYSICAL_TEMPLATE_IDS

`maneuvers.ts` 定数:

```ts
const PHYSICAL_TEMPLATE_IDS = new Set([
  100, // 交差点案内
  104, // 交差点案内 (まもなく)
  105, // 速度に応じたガイダンス発話
  314, // 自動車専用道路入口案内
  // 高速分岐 / 合流 / トンネル分岐
  303, // 高速推奨レーン
  304, // 渋滞を考慮した高速推奨レーン
  404, // 合流地点 (JCT 直後の本線/分岐確定に効く)
  422, // トンネル分岐案内
]);
```

`303` / `304` / `404` を含めた経緯: 100/104/105/314 だけだと **JCT で本線か
分岐かを Routes API に伝えるための情報が落ちる** ため、JCT 直後の合流点 (404) を
入れることで「JCT で右に曲がった後ここを通る」という方向情報が確定する。
spec/22 §11 で起きていた本線スルー問題への直接対策。

### 3.3 Block → GP の対応付け

`block.range.field_3` は **目的地までの残距離 (m)**。GP 列の累積距離
`cum_from_start[i] = sum(cum_distance_m[0..i])` に対して
`target_cum = total - block.range.field_3` を求め、最近傍 GP を選ぶ。

```ts
function findNearestGpIndex(cumFromStart: number[], target: number): number {
  let bestIndex = -1;
  let bestDiff = Infinity;
  for (let i = 0; i < cumFromStart.length; i++) {
    const diff = Math.abs(cumFromStart[i] - target);
    if (diff < bestDiff) { bestDiff = diff; bestIndex = i; }
  }
  return bestIndex;
}
```

完全一致しないことの方が多い (block の field_3 はメートル丸めで、GP cum も
メートル丸め) ため最近傍検索でよい。

### 3.4 announcement の dict / list 揺れ

guide.json の `block.announcements` は **配列 (複数 announcement) と単一オブジェクト
(1 個だけ) の両形式が混在する** (Wire ライブラリの最適化で repeated field が
1 件のときに dict に倒れる、proto-to-json コンバータの仕様)。

```ts
function collect(anns: ListOrDict): Array<Ann> {
  if (!anns) return [];
  return Array.isArray(anns) ? anns : [anns];
}
```

を必ず通してから priority を抽出する。

### 3.5 構造マーカーフラグの判定

`isStructuralBranch` (案内地点側に追加するフラグベース判定):

```ts
function isStructuralBranch(point): boolean {
  const flags = point.flags;
  if (!flags) return false;
  if (flags.field_1?.field_1 !== undefined) return true; // 主要 maneuver
  const f3 = flags.field_3?.field_1;
  return typeof f3 === "number" && PHYSICAL_F3_VALUES.has(f3);
}
const PHYSICAL_F3_VALUES = new Set([1, 7, 10]); // 分岐 / 合流 / 高速ランプ
```

### 3.6 maneuver 集合の最終形

```ts
maneuverIndices = (announcement-driven) ∪ (structural-flag-driven) ∪ {0, last}
```

origin / destination は Routes API 呼び出しの出発・到着点として必須なので
常に含める。

shakuji-tsukuba (146 GPs) で計算した結果: 57 GPs。

---

## 4. 案内地点外 (Between Maneuvers) の判定

### 4.1 設計思想

「案内地点ピンポイント = 進路情報なし」「案内地点外 = 進路上の中継点 = 道路と
進行方向を一意に確定」という観察から、**Routes API を狙った道路に固定する
には案内地点外の方が効く** ことが分かった (spec/22 §11 とは異なる結論)。

ただし「案内地点外」を雑に拾うと **「素通りでも分岐選択を強制してしまう構造点」**
(名前付き交差点、IC 素通り点、JCT 取らない側、レア f3 値を持つ点等) が混入
し、Routes API がそこで方向を間違える。これらを除外した **excludeFromBetween**
集合を別途構築する必要がある。

### 4.2 isStructuralPoint の確定形

`maneuvers.ts` の `isStructuralPoint`:

```ts
function isStructuralPoint(point): boolean {
  if (!point) return false;
  if (hasPlaceName(point)) return true;
  const u104 = point.attr?.unknown_104;
  if (typeof u104 === "number" && u104 !== 1) return true;
  const flags = point.flags;
  if (!flags) return false;
  if (flags.field_1?.field_1 !== undefined) return true;
  const f3 = flags.field_3?.field_1;
  return typeof f3 === "number" && f3 !== 31;
}
```

各条件の根拠:

#### `hasPlaceName(point)` (= `label.place.text.sjis` が非空)

名前付き交差点・IC・JCT 等。素通りでも **交差点として方向選択点になる** ので
除外。shakuji-tsukuba では 16 GP 該当 (例: GP 10 比丘尼 / GP 67 三郷IC 素通り /
GP 69 三郷JCT 取らない側 / GPs 115-141 つくば市内の交差点群)。

#### `attr.unknown_104 !== 1`

実データ観察により判明した重要マーカー。意味は「連結道路数」と推定:

```
shakuji-tsukuba unknown_104 分布:
  1  → 97 GPs (単一連結 = 中継点・pass-through)
  4  → 41 GPs (4-way 交差点)
  7  → 4 GPs  (より複雑な交差点)
  14 → 4 GPs  (ロータリー / 多分岐?)
```

**`u104=1` 以外は全部交差点扱いで除外**。これがないと例えば「f3=31 / f4=null /
名前無し」でもつくば市内の素通り交差点 (GPs 113, 122, 123 等) が残ってしまい、
Routes API がそこで道を曲げる。

#### `flags.field_1` が立つ

JCT / IC / 料金所等の主要 maneuver。すでに maneuverIndices にも入るが、念のため
こちらでも除外。

#### `flags.field_3.field_1 !== 31`

`f3 = 31` (通常道なり) のみ pass-through 安全。それ以外 (1=分岐 / 4 / 6 / 7=合流 /
10=高速ランプ / 19) は全部構造点 → 除外。

#### `flags.field_4` は条件から外した

過去のリビジョンでは `f4 != null` も除外条件にしていたが、つくば市内などで
**`f3=31 / 名前無し` の素直な道なり点でも `f4=0` が立つ** ケースが多く、
つくばセクションの候補が枯渇する問題が出た。`f4` の semantics は spec/21 §Q-4
でも「分岐 GP のサブ分類補助と推定」程度の解像度しかなく、厳密に交差点を
意味するわけではないので除外条件から外した。

代わりに u104 を使うことで真の交差点を確実に弾けるようになっている。

### 4.3 excludeFromBetween 集合

```ts
excludeFromBetween = maneuverIndices ∪ {i | isStructuralPoint(points[i])} ∪ {0, last}
```

shakuji-tsukuba: 87 GPs (146 中)。残る pass-through 候補 = 59 GPs。

### 4.4 ±1 隣接除外

案内地点外の最終候補集合は、`excludeFromBetween` に含まれる index と **index ±1
で隣接する GP も除外** する:

```ts
for (let i = 1; i < lastIndex; i++) {
  if (excludeSet.has(i)) continue;
  if (excludeSet.has(i - 1) || excludeSet.has(i + 1)) continue;
  betweenInner.push(i);
}
```

理由は 2 つ:

1. **マップ上の視覚重なり防止**: GP 18 (大泉料金所) のすぐ後ろの GP 19 は
   55m しか離れておらず、地図上でバッジが重なる。
2. **構造点至近では方向情報が薄い**: GP 19 が乗っている link は GP 18 の交差点
   から伸びる link なので、Routes API への進行方向情報として GP 18 と冗長。

shakuji-tsukuba: 36 GPs (±1 隣接除外後)。

---

## 5. 候補ソースの polyline 切替 (重要)

### 5.1 GP-based の限界

§4 までで構築した GP-based 案内地点外候補には **致命的な分布偏り** がある。

shakuji-tsukuba (74km) での候補分布:

| 区間 | 区間長 | 候補数 |
|---|---|---|
| 一般道 (0-7km) | 7km | 19 |
| 高速 (7-50km) | 43km | 17 (大半は 33-42km の JCT 周辺に集中) |
| つくば市内 (50-74km) | 24km | **0** |

つくば市内が 0 になる原因: 外部API GP は「案内が発生する地点」を中心に記録
される設計なので、つくば市内のような交差点密集地では `u104=4` (4-way 交差点)
の GP しか存在しない。**真の mid-segment GP がデータ自体に存在しない**。

### 5.2 polyline-based に切替

外部 polyline は道路形状そのもの (shakuji-tsukuba で 960 頂点 / 74km = 平均
80m / 頂点)。**大多数の頂点が mid-segment** で、交差点で「曲がる」頂点は
ごく一部。これを案内地点外の候補ソースに使うと、つくば市内にも自然に分布する。

`main.ts:samplePolylineBetween()` の責務:

1. 外部 polyline 各頂点の累積距離 (haversine) を計算
2. 内側頂点 (1..N-2) を候補にして、案内地点側で既に拾った cumDist を seed と
   する farthest-point sampling
3. `targetGap = stride * 1000m` で desired count を算出。bestMin が
   `targetGap * 0.4` を下回ったら採用打ち切り (案内地点と被るのを防ぐ)
4. 各頂点の局所接線方向 (前後の polyline 頂点でなす方位角) を heading に
   採用

### 5.3 stride のセマンティクス変更

GP-based 時代は「Every Nth = 候補数の 1/N」だったが、polyline には頂点が桁違い
に多いのでこのままだと desired count が爆発する。**距離直接指定** に変更:

| UI 表示 | targetGap (m) |
|---|---|
| All     | 1000  (1km / pick) |
| Every 2nd | 2000 |
| Every 4th | 4000 |
| Every 8th | 8000 |

74km route で Every 4th → 約 19 picks。chunk_size=25 で 1 chunk に収まる。

### 5.4 GP-based fallback

`currentExternal.length < 2` (= 外部 polyline が無いサンプル、tokyo-gotemba 等
3 サンプル) の場合は旧 GP-based 距離 sampling に自動 fallback。fallback でも
±1 隣接除外と FPS は同じロジックで動く。

### 5.5 maneuver 側は GP-based のまま

「案内地点」の方は意味的に GP に紐付くので polyline 化はしない。GP index を
そのまま stride で間引く。

---

## 6. Sampling アルゴリズム — Farthest-Point Sampling (FPS)

### 6.1 採用理由

候補が距離上で偏っている (例: 大泉前後 4km に 19 候補、JCT 周辺 1km に 17 候補、
つくば section に 0 候補) ため、単純な「targetGap 以上離れた候補を貪欲に拾う」
アルゴリズムだと、候補 cluster で targetGap を満たす次の点が枯れて **desired
の 1/3 程度しか拾えない** (実測: stride=4 で desired=9 に対し 2 picks)。

FPS なら原点と終点を seed に「既選択点群から最も遠い候補」を毎回採用するので、
desired_count を必ず満たし、かつ大きいギャップから優先的に埋まる。

### 6.2 アルゴリズム

```ts
function pickByDistance(candidates, cumDistance, stride): number[] {
  if (stride <= 1) return candidates.slice();
  const total = cumDistance[lastIndex] ?? 0;
  const desired = Math.max(1, Math.ceil(candidates.length / stride));
  const seeds = [0, total];
  const selected = new Set<number>();
  for (let pick = 0; pick < desired; pick++) {
    let bestIdx = -1, bestMin = -Infinity;
    for (const c of candidates) {
      if (selected.has(c)) continue;
      const d = cumDistance[c];
      const minDist = Math.min(...seeds.map(s => Math.abs(d - s)));
      if (minDist > bestMin) { bestMin = minDist; bestIdx = c; }
    }
    if (bestIdx < 0) break;
    selected.add(bestIdx);
    seeds.push(cumDistance[bestIdx]);
  }
  return Array.from(selected).sort((a, b) => a - b);
}
```

polyline 版 `samplePolylineBetween` は seeds として **既に拾った案内地点の
cumDist** も最初から入れる:

```ts
const seeds = [...alreadyPickedDistances, totalLength];
if (!seeds.includes(0)) seeds.push(0);
```

これにより polyline picks が案内地点と重ならない。

### 6.3 早期打ち切り

```ts
if (bestMin < targetGap * 0.4) break;
```

bestMin が targetGap の 40% 未満まで落ちたら採用を止める。これは「もう全部の
点が seed に近すぎる = 詰めすぎ」の判定。これを入れないと desired count を
出すために無理やり cluster 内から拾い、視覚的に重なる。

---

## 7. Routes API 呼び出し — `src/routes-api.ts`

### 7.1 chunk 分割

Routes API v2 は intermediate waypoint 25 個までのハードキャップ (spec/22
§11.1)。waypoint 数が 25 を超える場合は chunk 分割して順に呼び、結果を結合する。

```ts
function sliceIntoChunks(waypoints, intermediateMax) {
  const stepSize = intermediateMax + 1;  // 1 chunk = 1 origin + N intermediates + 1 dest
  // chunks[k] の dest が chunks[k+1] の origin (重複 1 点で接続)
  // 例: 60 点 / chunkSize=25 → chunk0: [0..26], chunk1: [26..52], chunk2: [52..59]
}
```

### 7.2 Waypoint 表現

```ts
interface WaypointInput {
  lat: number;
  lng: number;
  heading?: number;   // 0-359 compass bearing。指定すると location.heading に乗せる
}

interface ApiWaypoint {
  location: {
    latLng: { latitude, longitude };
    heading?: number;
  };
  via?: boolean;      // 中間点の挙動切替 (§7.3)
}
```

### 7.3 `via: true` の挙動

intermediates にだけ立てるオプション (origin/destination には付けない)。

- `via: false` (= デフォルト stopover): Routes API は **「この waypoint で停車
  して再出発」** として扱う。停車に向いた道路 (= 一般道、停車スペースのある
  道) を選びやすい。**高架高速の真下の側道がある場合に下道を選んでしまう原因**。
- `via: true`: 「単なる通過点」として扱われ、停車可否を考えない。高速本線等の
  停車できない道もそのまま走り抜ける選択肢に入る。

副作用: response の `legs` 配列が via 点で分割されない。turn-by-turn 用途
では legs が要るが、polyline だけ取得する場合は問題なし。

**実機検証で「下道に降りてしまう」問題は via=true で解決した** (本ドキュメント
作成時点)。

### 7.4 `Waypoint.location.heading`

進行方向 (compass bearing 0-360°)。Google Routes API の docs では「pickup の
side of road を指定する用」とされているが、実機では **map-matching の snap
先を進行方向で絞る効果がある**。

特にランプ系 (本線→ランプ、ランプ→本線) や、高架の本線と地表のバイパスが
近接する区間で snap 誤判定を防ぐ。

dev tool では polyline の局所接線方向 (頂点 i-1 → i+1 のチョード) を
heading として送る。

### 7.5 リクエスト body

```ts
{
  origin: <Waypoint>,
  destination: <Waypoint>,
  intermediates: [<Waypoint>],
  travelMode: "DRIVE",
  routingPreference: "TRAFFIC_UNAWARE",
  polylineQuality: "HIGH_QUALITY",
}
```

`X-Goog-FieldMask` で必要なものだけ要求してクォータ節約:

```
routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration
```

### 7.6 polyline decode

`src/polyline.ts` に Google encoded polyline algorithm のデコーダがある
(参考: <https://developers.google.com/maps/documentation/utilities/polylinealgorithm>)。
`5e-5` でデコードするのが正しい (`1e-5` でもなく `1e-6` でもなく `5*10^-7` の
内部精度)。

---

## 8. UI と挙動

### 8.1 layer toggle

| layer | 色 | 内容 |
|---|---|---|
| External polyline | 青 #1e88e5 | 外部API (datum 変換後) |
| Routes API split polyline | chunk 別 (赤系 hue shift) | Routes API 結合結果 |
| All guide points | 橙 dot | 全 GP (146 点 etc.) |
| Sent waypoints (with order) | origin=緑 / dest=赤 / 中間=紫、chunk 境界=黒太枠 | 実際に送った waypoint と順序 |

### 8.2 sampling controls

| | 選択肢 | 意味 |
|---|---|---|
| 案内地点 | None / All / Every 2nd / Every 4th / Every 8th | maneuverIndices からの index-stride 間引き |
| 案内地点外 | None / All / Every 2nd / Every 4th / Every 8th | targetGap = stride*1km。polyline-based FPS。GP-based fallback |
| Chunk size | 1〜25 (default 25) | 1 chunk あたり intermediate 上限 |
| Set heading | toggle (default ON) | Waypoint.location.heading を polyline 接線で |
| Use via waypoints | toggle (default OFF) | intermediates に via:true |

### 8.3 確定運用設定 (Android 統合での採用方針)

§0.1 と同内容、再掲:

- 案内地点 = **None**
- 案内地点外 = **Every 4th** (約 19 picks / 74km route)
- Chunk size = **25**
- Set heading = **ON**
- Use via waypoints = **ON**

これで polyline inflation が 1.0x 付近 (= 外部ルートとほぼ完全一致)、つくば
section にも waypoint が散る。Android 側 (Kotlin) 実装も同じ値をデフォルトと
する (§11.2.3)。

### 8.4 Status / Metrics 表示

Status: 各 chunk の intermediate 数 / dist / vertex 数 を列挙。
Metrics: Google total / External (haversine 概算) / Inflation 比 (Google /
External) を表示。Inflation 1.0x 付近なら良好、1.1x 以上は何かが間違っている。

### 8.5 Custom mode (任意地点での検証)

Sample dropdown 末尾の **「Custom (click on map)」** を選択すると入る。

#### 操作

1. 地図をクリックすると waypoint が末尾に追加される (1 点目=origin、最終=
   destination、中間=経由地)
2. 入力点は teal 色の点線丸バッジで番号付き表示。custom panel に座標一覧が出る
3. `Remove Last` で 1 点取り消し、`Clear All` で全消去
4. `Fetch & Render` で 2 段階実行:
   - **Step 1**: ユーザ click 点をそのまま Routes API に投げて reference polyline を
     取得 (FPS 通さない、`useVia: false`)。これが外部ルート役 (青)
   - **Step 2**: Step 1 の polyline に対して `buildCustomSentWaypoints` (§8.5.2) で
     waypoint を構築し、chunk Routes API で test polyline を取得 (赤系)
5. Metrics は Reference km / Test km / Inflation (= test / reference-haversine)

#### 8.5.1 経由地強制 waypoint の必要性

通常の `buildSentWaypoints` (§5-§6) は reference polyline を均等 FPS するだけで、
ユーザが指定した経由地 (= ピンポイントで通って欲しい交差点) を waypoint に
含めない。FPS が経由地至近の頂点を選ばなかった場合、Routes API は経由地を
スキップしてショートカットしてしまい、test polyline が reference から大きく
ずれる現象が出た。

修正は単純で、**ユーザ click 点を強制で waypoint 列に注入**する。これらは
`useVia=true` 設定下では pass-through 扱いになり、Routes API は必ずそこを通る。

#### 8.5.2 `buildCustomSentWaypoints` のロジック (`src/main.ts`)

```
入力: referencePolyline, betweenStride, useHeading
       ユーザ click 点列 (customInputs)
出力: WaypointInput[]

A) reference polyline 各頂点の累積距離 (haversine) を計算
B) 各ユーザ click 点を polyline 上の最近傍頂点に projection
   → 累積距離と接線方向 (heading) を取得
C) ユーザ click 点を全て collected[] に強制投入 (isUserPoint=true)
D) ユーザ点 cumDist 群を seed に polyline 内側頂点を FPS sampling
   - 候補 = 内側頂点 ∖ ユーザ projection 頂点
   - desiredAdditional = ceil(totalLength / targetGap) - userCount
   - 早期打ち切り: bestMin < targetGap * 0.4
E) cumDist 順 sort + 5m 以内近接 dedup (user 点優先で残す)
```

これで「ユーザが指定した経由地は必ず通る + その間は polyline 形状を補助点で
追従させる」という意図通りの test polyline が出る。

#### 8.5.3 Custom mode の Android 統合への示唆

ユーザ click 強制 waypoint のロジックは、本番側で **「外部API が返した
特定の重要地点 (例: ユーザが行先候補として選んだ POI、ピン留めした地点) を
必ず経由する Routes API 呼び出し」** が必要になった時に再利用できる
(§11.2.3 `ExtApiRouteRefiner` に `forcedWaypoints` 引数を追加する形)。
当面の確定運用設定 (§0.1) では使わないが、将来拡張の余地として頭に入れておく。

---

## 9. データ構造の細部 (proto 由来)

guide.json ダンプから安定的に拾える / 拾えないフィールドを列挙。Wire Java の
proto 出力を python で json.dump した結果なので、フィールド名が
`field_1` / `field_3` / `unknown_104` 等の機械生成名になっているのが特徴。

### 9.1 GuideFile.body の階層

```
body
├ route_info
│  ├ poi_type_labels (destination/waypoint/intersection/origin/poi_ref)
│  └ points[]                     # GP 列。len = 146 for shakuji-tsukuba
│     ├ attr
│     │  ├ kind                   # 連番ではない GP 識別子
│     │  ├ coord {lon_e6, lat_e6} # arc-millisecond ÷ 3,600,000
│     │  ├ link_id_a / link_id_b  # 進入/退出 link id
│     │  ├ unknown_101..107       # 連結性 / lane 数等の指標
│     │  │  └ unknown_104 が「連結道路数」(1=単一 / 4=4-way / 7,14=複雑)
│     │  └ cum_distance_m / cum_time_ms
│     ├ flags
│     │  ├ field_1 (有/無)        # 有なら主要 maneuver (JCT 等)
│     │  ├ field_3.field_1        # 31=道なり / 1=分岐 / 7=合流 / 10=高速ランプ / 4,6,19=レア
│     │  └ field_4                # 分岐サブ分類 (semantics 不明確、フィルタには使わない)
│     ├ label
│     │  ├ place.text.sjis        # 名前付き交差点・IC・JCT 等の名称 (SJIS バイト列)
│     │  ├ direction_a.text.sjis  # 方面名称 (SJIS)
│     │  ├ option_105             # 警告 template_id (例: 312=誤進入注意喚起)
│     │  └ empty_101..103         # 道路名・道路番号
│     └ sub_path_samples / props_15 / coord_alt_1
└ guide
   ├ templates[]                  # template_id → 名称の辞書
   ├ blocks[]                     # 発話 block 列
   │  ├ header.codes / flag_bitmask
   │  ├ range
   │  │  ├ field_1 / field_2      # 距離区間
   │  │  ├ field_3                # 目的地までの残距離 (m) ← block→GP 対応に使う
   │  │  └ field_4
   │  ├ announcements             # ※ list / dict 揺れ あり (§3.4)
   │  │  ├ priority               # = template_id
   │  │  └ content.text           # 発話テキスト (UTF-8)
   │  └ trailer
   └ milestones                   # bytes (Wire decode 失敗するので touch しない)
```

### 9.2 文字コード上の注意

guide.json は元々 proto を python で json 化した時点でデコードされており、
`text.sjis` は **SJIS バイト列を UTF-8 string として直接記録** している
(プロト出力ツール側で SJIS デコードを通している)。直接 `text.sjis` を
読めばそのまま日本語文字列が取れる。

```python
{"text": {"sjis": "川口ＪＣＴ"}, "kana_sjis": {"sjis": "ｶﾜｸﾞﾁｼﾞｬﾝｸｼｮﾝ"}}
```

実装側 (ext-api/) では生 proto から取るので別途 `decodeSjis()`
が必要 (`ext-api/CLAUDE.md` §文字コード参照)。

### 9.3 cum_distance_m / cum_time_ms の累積方向

GP `cum_distance_m` は **「前 GP からこの GP までの増分」**。先頭から sum
すると total route length と一致する (shakuji-tsukuba: 74400m)。
`block.range.field_3` は **「目的地までの残距離」** (= total - cum_from_start)。
この食い違いに注意。

---

## 10. 数値検証 (shakuji-tsukuba)

### 10.1 各フェーズでの候補数推移

| 集合 | 個数 |
|---|---|
| 全 GP | 146 |
| 案内地点 (= maneuverIndices) | 57 |
| └ うち announcement 由来 | 24 |
| └ うち structural-flag 由来 | 約 33 |
| 名前付き素通り構造点 | 16 |
| u104 != 1 の追加交差点 | 約 14 |
| excludeFromBetween (合計) | 87 |
| GP-based 案内地点外候補 (±1 隣接除外後) | 36 |
| polyline-based 案内地点外候補 (内側頂点) | 958 |

### 10.2 stride=4 picks (案内地点外)

| 方式 | 個数 | 採用位置 (km) | 備考 |
|---|---|---|---|
| GP-based + index stride (旧旧) | 15 | 大泉前後に偏る | 棄却 |
| GP-based + 距離 greedy (旧) | 2 | 2.8 / 33.7 | 候補枯渇で枯れる |
| GP-based + FPS (前回) | 9 | 3.1/3.9/5.1/6.0/33.7/39.0/39.7/40.5/41.7 | つくば 0 |
| polyline-based + FPS (現行) | ~19 | 約 4km 間隔で全区間 | **つくば section にも分布** |

### 10.3 inflation 比

GP-based のとき: 1.18x (本線/側道誤判定が稀発)
polyline-based + heading + via=true: 約 1.00x (実測でほぼ完全一致)

---

## 11. Outstanding 課題と継続作業

### 11.1 サンプル拡充

現状 polyline geojson があるのは shakuji-tsukuba のみ:

```
ext-api/analysis/sample/shakuji-tsukuba/extracted/route/route-polyline.geojson
```

tokyo-gotemba / tokyo-nagoya-hiroshima / hiroshima-ferry-beppu には `extracted/
route/` 自体が無く GP-based fallback 動作を確認できない。`ext-api/
analysis/sample/<id>/protobuf/route_to_json.py` を全サンプルに走らせれば
polyline は再生成できる (route-polyline.geojson 出力ロジックあり)。

priority は中。Custom mode (§8.5) で任意地点での検証は可能になったので、
shakuji-tsukuba 1 サンプルでの観測に依存せず別地域 (山岳路 / 都市高速ダブル
ルート / フェリー区間等) を即試せる状態にはなっている。とはいえ正本サンプル
での再現性チェックは別途必要。

### 11.2 本番側 (Kotlin) への落とし込み

§0.1 の決定された運用設定を前提とした **Android 本番統合の実装計画**。

#### 11.2.1 採用設定下で必要な要素

最終方針 (案内地点=None / 案内地点外=Every 4th polyline-based / heading=ON /
via=ON) では、入力として **外部ルートの polyline (LineString)** と
**origin / destination** さえあれば良い。proto `guide.json` の解析 (§3-§4 の
maneuver / 構造点判定) は **不要**。

これは Kotlin ポートの実装複雑度を大きく下げる:

| dev tool で実装 | 本番で必要か | 理由 |
|---|---|---|
| §2 datum 変換 | **不要** | 実装側 `ExtApiHeaders` で `x-up-datum: wgs84` を全リクエストに付与済み。ライブラリ経由 polyline は WGS84 |
| §3 案内地点判定 | **不要** | 採用設定が案内地点=None |
| §4 構造点除外 | **不要** | 案内地点外 candidate を polyline 頂点にしているため、GP 側の構造点フィルタは出番なし |
| §5 polyline 候補化 | **必要** | 外部 polyline 内側頂点 (1..N-2) を candidate に |
| §6 FPS sampling | **必要** | targetGap=4000m / desiredCount = ceil(total/targetGap) / 早期打ち切り bestMin < targetGap*0.4 |
| §7.1 chunk 分割 | **必要** | stepSize = intermediateMax + 1、chunk 境界で waypoint を共有 |
| §7.3 via=true | **必要** | intermediates のみに付与 (origin/dest には付けない) |
| §7.4 heading | **必要** | 各 waypoint で polyline 局所接線方向を計算 |
| §7.6 polyline decode | **必要** | Routes API レスポンスの encoded polyline を頂点列に戻す |

#### 11.2.2 落とし込み先モジュールと API 境界

外部API ライブラリの中核ロジックは別管理のプライベートリポジトリ
(`ext-api/`) に閉じている。Android 側との接合は、Map 表示の
ための polyline を返す関数として行う:

```
[ext-api] (private repo)
   ├ RouteResponse (内部)
   └ extPolyline: List<LatLng>  ← 外部ルート polyline (WGS84)

         │
         ▼  境界

[OneNavi composeApp / feature/navigation]
   └ ExtApiRouteRefiner          ← 本ドキュメントで定義する Kotlin 実装
       入力: extPolyline + origin + destination
       出力: refinedPolyline: List<LatLng>  (Routes API で形状追従させた結果)
```

#### 11.2.3 Kotlin 側のクラス構成 (案)

`feature/navigation/` (新規) または `composeApp` 配下に以下を配置:

```kotlin
// 純粋関数群。proto / guide には依存しない。
object ExtApiRouteRefiner {

    /** §5-§6: FPS で polyline 内側頂点をサンプリングし、heading 付き waypoint 列を返す */
    fun samplePolylineWaypoints(
        extPolyline: List<LatLng>,
        targetGapMeters: Double = 4000.0,
    ): List<RoutesApiWaypoint>

    /** §7.1: stepSize = intermediateMax + 1 で chunk 分割 */
    fun chunkWaypoints(
        waypoints: List<RoutesApiWaypoint>,
        intermediateMax: Int = 25,
    ): List<List<RoutesApiWaypoint>>

    /** §7.5-§7.6: 各 chunk を Routes API に投げて polyline を結合 */
    suspend fun computeChunkedRoute(
        chunks: List<List<RoutesApiWaypoint>>,
        useVia: Boolean = true,
    ): List<LatLng>
}

@Immutable
data class RoutesApiWaypoint(
    val latLng: LatLng,
    val heading: Int? = null,  // 0-359 (compass bearing)
)
```

エントリポイント関数 (feature 層で呼ぶ):

```kotlin
suspend fun refineExtApiRoute(
    extPolyline: List<LatLng>,
    origin: LatLng,
    destination: LatLng,
): List<LatLng> {
    val intermediates = ExtApiRouteRefiner.samplePolylineWaypoints(extPolyline)
    val all = listOf(origin.toWaypoint()) + intermediates + listOf(destination.toWaypoint())
    val chunks = ExtApiRouteRefiner.chunkWaypoints(all)
    return ExtApiRouteRefiner.computeChunkedRoute(chunks, useVia = true)
}
```

#### 11.2.4 Routes API クライアントの実装

Ktor 3.3 の `HttpClient` で `https://routes.googleapis.com/directions/v2:computeRoutes`
を POST する。ヘッダ:

- `X-Goog-Api-Key`: BuildKonfig 経由で `local.properties` から (既存パターン踏襲)
- `X-Goog-FieldMask`: `routes.polyline.encodedPolyline,routes.distanceMeters,routes.duration`

リクエスト body は §7.5 そのまま。`via=true` は intermediates にのみ。
`heading` は `location.heading` に Int で乗せる (0-359 に正規化)。

polyline decoder (§7.6 / `dev-tools/route-compare/src/polyline.ts`) は Kotlin に
移植。1 ファイル ~50 行。

#### 11.2.5 統合段取り

1. **新規モジュール作成** (`feature/navigation/`): `matsumo.primitive.kmp.android`
   convention plugin を適用 (本番統合は当面 Android のみで良い)
2. **Routes API クライアント** (`core/datasource/routes/`): Ktor + BuildKonfig 経由
   API key
3. **`ExtApiRouteRefiner`** (`feature/navigation/`): §11.2.3 のクラス群
4. **polyline decoder**: `core/common/` に置く
5. **画面側統合**: `feature/home/` のルート表示で外部ライブラリ polyline ではなく
   refined polyline を Google Maps overlay に渡す
6. **dev サンプル拡充**: §11.1 の他 3 サンプルでも polyline geojson を生成して
   Kotlin 実装の挙動を確認

#### 11.2.6 検証方針

dev tool (TypeScript) で確認した shakuji-tsukuba の inflation ~1.00x が
Kotlin 実装でも再現できることをまず確認する。具体的には:

- shakuji-tsukuba の extPolyline を fixture として持ち、`ExtApiRouteRefiner`
  の出力を dev tool の出力と比較 (waypoint 緯度経度・heading・chunk 境界)
- Routes API レスポンスの inflation 比 (Google distance / external haversine)
  が 1.05x 以下であること
- 視覚確認は composeApp 上で外部 polyline と refined polyline を別レイヤで
  描画して目視

`ext-api/src/main/kotlin/.../guidance/` 配下に
ある `GuideProtoMapper` は本実装路線では参照しない (案内地点判定が不要なため)。
将来 maneuver 併用設定に戻したくなった場合のみ §3-§4 のロジックを Kotlin
ポートする。

### 11.3 spec/20 反射注入路線との関係

spec/22 §11.7 D-2211 の結論は「polyline 形状追従では spec/20 反射注入が唯一
優位」だった。本ドキュメントの結果はこれを **部分的に覆す**:

- 案内地点外を polyline-based に切り替えた上で heading + via=true を併用すれば、
  Routes API でも **inflation 1.0x = 形状ほぼ完全一致** が実機サンプルで達成
  できる (ただし shakuji-tsukuba 1 サンプルでの観測)。
- ただし spec/20 反射注入は API 呼び出しゼロ・オフラインでも動く強みがあり、
  公開 SDK の意図しない使い方なので Google が変更すれば壊れる脆さもある。

→ Android 本番統合では **Routes API + polyline FPS 路線を第一選択** とする
(§0.1 / §11.2)。spec/20 反射注入はクォータ 0 / オフライン動作という強みがある
ため、benchmarking で実機ネットワーク不通時の挙動を確認した上で fallback として
保持するか判断する。spec/22 D-2211 はこの結論で更新する価値あり。判断材料:

| | Routes API + polyline FPS (本ドキュメント) | spec/20 反射注入 |
|---|---|---|
| API 呼び出し | あり (chunk 数 = ceil(waypoint/25)) | 0 |
| クォータ消費 | あり | なし |
| オフライン | 不可 | 可 |
| Google SDK 更新耐性 | ◎ | △ (内部 class 名依存) |
| 形状追従精度 | ~1.00x (頂点 polyline) | 1.00x (注入 polyline 自身) |
| 実装複雑度 | 中 (proto 解析 + sampling) | 高 (reflection / JNI) |

### 11.4 残るリスク要素

- **polyline-based 案内地点外でも交差点頂点に当たる可能性**: 統計的に少ない
  (≈ 2-3% の頂点が交差点で曲がる) が、ゼロではない。次の改善案: polyline 頂点
  ごとに「最近傍 GP との距離」を計算し、構造点 GP の 50m 以内にある polyline
  頂点を候補から外す追加フィルタを入れる。
- **proto フィールド名の機械生成依存**: `unknown_104`, `field_3.field_1` 等は
  proto 定義側で `optional int32 num_links_at_node = 104;` のような正本名が
  あるはず。ext-api/ 側で proto schema を整備すれば名前が安定する。
  本ドキュメントの判定ロジックは「フィールド番号」を信頼している前提で、
  proto schema が変わると壊れる。
- **datum 変換誤差**: Hosoi 簡易式は ~10m 級。高精度が必要なら国土地理院
  TKY2JGD ローカル参照点表を使う実装に切り替え。デバッグ目視には現状で十分。

---

## 12. 再現手順 (新規 context から開始する場合)

新しい session で本ドキュメントだけを手がかりに作業再開する想定の手順:

1. dev-tools/route-compare/ が存在しなければ §1.2 の構成で再作成。`Makefile`
   に `route-compare-*` ターゲットを追加。
2. `make route-compare-setup` で npm install + .env 雛形作成。`.env` に
   `VITE_GOOGLE_API_KEY=<key>` を入れる。Routes API / Maps JS API 有効化済み
   key が必要。
3. `make route-compare-dev` でサーバ起動 (port 5174)。Sample dropdown で
   shakuji-tsukuba を選択。
4. UI で `案内地点 = None / 案内地点外 = Every 4th / heading=ON / via=ON` を
   設定し Fetch & Render。Inflation 1.00x 付近 + つくば section に紫バッジが
   分布していれば本ドキュメント時点の挙動を再現できている。
5. Inflation が悪い / waypoint が偏っているなら §4-§6 のロジックを順に検証:
   - excludeFromBetween 集合の中身 (`maneuvers.ts`)
   - polyline サンプリング (§5, `samplePolylineBetween`)
   - via / heading 設定の確認
6. 検証スクリプトは python で `ext-api/analysis/sample/<id>/
   extracted/guide/guide.json` をパースする形で書ける。本ドキュメント §10 の
   各数値はそうやって再現可能。

---

## 13. 参考リンク

- `docs/spec/18_external_api_migration_plan.md` — 全体の移行計画
- `docs/spec/20_navigationview_external_route_bridge_investigation.md` — 反射注入路線
- `docs/spec/21_ext_api_guide_proto_and_announcement.md` — proto / announcement 仕様
- `docs/spec/22_route_token_and_custom_navigator_evaluation.md` — Custom Navigator 評価
- `dev-tools/route-compare/src/` — 本ドキュメントの実装
- `ext-api/CLAUDE.md` — 実装側のハマり所
- Google Routes API v2: <https://developers.google.com/maps/documentation/routes>
- Google encoded polyline: <https://developers.google.com/maps/documentation/utilities/polylinealgorithm>
