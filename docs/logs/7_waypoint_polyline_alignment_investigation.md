# 7. 経由地ありルート polyline の道路ズレ調査

## 目的

経由地を含むルート探索後に表示される polyline が、Google Maps の道路表示からずれて見える原因を、曖昧な推測で断定せずに切り分ける。

## 調査方針

- アプリ側の座標変換・geometry 組み立て・描画入力を確認する。
- 経由地なし / 経由地ありの外部ナビ API 出力を同条件で採取して比較する。
- Google Routes API の route polyline を別基準として取得し、外部ルート polyline との距離差を定量化する。
- 確認できた事実、否定した仮説、未確定事項を分けて記録する。

## これまでに確認済みの事実

- `docs/spec/23_route_compare_dev_tool.md` には、外部ナビ API ライブラリ経由の route polyline は WGS84 として扱う前提が記録されている。
- ライブデータでも、WGS84 入力かつ WGS84 指定で取得した polyline の先頭点は入力 origin 付近にあり、旧日本測地系から WGS84 へ追加変換すると約 400m 以上離れるケースを確認した。
- したがって、現在の通常 route path に対して「旧日本測地系として追加変換する」仮説は棄却する。
- 直前の修正では、exact origin / destination を dense polyline に混ぜ込む描画上の接続線を除去した。この修正は endpoint 周辺の直線混入には効くが、スクリーンショットで見える経由地ありルート全体の道路ズレを単独では説明しきれていない。

## 現時点の未確定事項

- 経由地ありの場合だけ、外部ナビ API の route polyline 自体が Google Maps の道路形状と大きく異なるのか。
- OneNavi 側で経由地あり route の geometry を誤って結合・間引き・座標順序変換しているのか。
- UI 上で古い Navigating polyline / RoutePreview polyline / selected waypoint preview が重なり、見た目としてズレているのか。

## 2026-06-03 ライブ調査

### Google Routes API 比較

Google Routes API を別基準として比較しようとしたが、手元の API key は CLI からの呼び出しが制限で拒否された。

- Android 用 key: Android application 制限により拒否
- dev-tools 用 key: HTTP referrer 制限により拒否

このため、Google Routes API との live 比較は未実施。以降は外部ナビ API の method 差分比較で原因を切り分けた。

### 経由地 method の比較

現行実装は DSR クエリで経由地に `viaN_method:3` を付けている。これを一時的に差し替えて live route polyline を取得した。

試した値:

| method | 結果 |
| --- | --- |
| 0 | レスポンスを route ZIP として解釈できず失敗 |
| 1 | レスポンスを route ZIP として解釈できず失敗 |
| 2 | 成功 |
| 3 | 成功（現行実装） |
| 4 | レスポンスを route ZIP として解釈できず失敗 |
| 5 | レスポンスを route ZIP として解釈できず失敗 |

成功した `method=2` と `method=3` は、距離・点数がほぼ同じなのに座標だけがずれる。

| scenario | method | polyline 点数 | origin→先頭 | destination→末尾 | waypoint→polyline |
| --- | ---: | ---: | ---: | ---: | ---: |
| Tokyo → Yokohama via Shinjuku | 2 | 1104 | 140.8m | 108.4m | 48.6m |
| Tokyo → Yokohama via Shinjuku | 3 | 1104 | 548.5m | 567.6m | 366.5m |
| Shakuji → Tsukuba via Nerima | 2 | 1185 | 9.2m | 13.8m | 1.5m |
| Shakuji → Tsukuba via Nerima | 3 | 1185 | 460.1m | 460.4m | 399.3m |

`method=3` は現行実装と同じで、経由地ありのときだけ端点・経由点から約 400〜560m 離れる。`method=2` は同じ route shape を返しつつ、端点・経由点が妥当な道路スナップ位置に戻る。

### datum 変換との対応

`method=2` の polyline と `method=3` の polyline を同一 index で比較した。

| scenario | raw 差分 mean | `method=3` に WGS→Tokyo 逆変換後の差分 mean | max |
| --- | ---: | ---: | ---: |
| Tokyo → Yokohama no via | 0.0m | 462.0m | 462.5m |
| Tokyo → Yokohama via Shinjuku | 464.1m | 2.6m | 2.7m |
| Shakuji → Tsukuba no via | 0.0m | 459.8m | 460.5m |
| Shakuji → Tsukuba via Nerima | 462.6m | 2.5m | 2.6m |

解釈:

- 経由地なしでは `method` が route に関与しないため、`method=2` と `method=3` の polyline は完全一致する。
- 経由地ありでは、`method=3` の polyline は `method=2` から平均約 463m ずれる。
- `method=3` の座標に WGS→Tokyo 方向の逆変換をかけると、`method=2` の座標と 3m 未満で一致する。

これは「OneNavi が常に座標系を取り違えている」では説明できない。経由地なし route は正しく WGS84 で返っているため。

現時点で最も根拠が強い原因は、`viaN_method:3` を付けた経由地あり DSR route binary の polyline だけが、`datum=wgs84` 指定下でも約 1 回分の datum 変換が余計にかかった座標として返っていること。

### 否定した仮説

- **OneNavi の lat/lon 入れ替え**: ずれ量が数百 m の datum 変換相当で、lat/lon 入れ替えのような破滅的な位置飛びではない。
- **RouteProtoMapper の全体的な単位誤り**: 経由地なし route は既存サンプル・live とも道路付近に出る。method=2 / method=3 の同一点数比較でも、変換差だけが出ている。
- **Google Maps 側の道路データ差だけ**: method=2 と method=3 は同じ外部 route の距離・点数を保ったまま座標だけが datum 変換分ずれるため、道路ネットワーク差だけでは説明できない。
- **exact origin / destination connector だけの問題**: connector 除去は端点直線混入には効くが、method=3 の全 polyline が平均約 463m ずれる事実は別問題。

## 暫定結論

根本原因は、経由地あり route の DSR クエリで `viaN_method:3` を使っていること。`method=3` では、経由地ありの ROUTE polyline だけが over-converted な座標で返り、Google Maps 上では道路から北西方向に約 400〜560m ずれて見える。

経由地なし通常ルートが正しく見える理由は、通常ルートには `viaN_method` が存在せず、この over-conversion path に入らないため。

## 修正候補

- DSR 経由地指定を `viaN_method:2` に変更する。
- `DsrQueryBuilderTest` に waypoint ありの rsp1 assertion を追加し、`vposN_coord` と `viaN_method:2` を固定する。
- live test では、経由地あり route の `origin→polyline先頭`、`destination→polyline末尾`、`waypoint→polyline` が数百 m にならないことを threshold 付きで確認する。
- dense polyline に exact origin / destination を混ぜない方針は維持する。駅・施設中心など、正しい route でも道路スナップで数十〜百 m 程度離れることはあるため。
