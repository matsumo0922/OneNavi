# Android Auto VirtualDisplay 描画枠の実測メモ

調査日: 2026-06-09。対象: Android Auto DHU + `CarVirtualDisplayProbeService`。

---

## 結論

VirtualDisplay / Presentation の raw Surface は `1190x700` のまま固定で、split 状態でも Surface 自体のサイズは変わらない。
一方、host が通知する `visibleArea` は左右の安全 inset を含むため、そのまま OneNaviApp の描画枠に使うと左右に余白が残る。

OneNaviApp は、probe の **青枠**で示した領域へ描画する方針にする。

```
observedLeft  = visibleLeft - horizontalSafetyInset
observedRight = visibleRight + horizontalSafetyInset

horizontalSafetyInset = min(visibleLeft, surfaceWidth - visibleRight)
```

実測値:

| 状態 | host visible | 青枠(observed frame) | 薄青枠(host slot) |
|---|---:|---:|---:|
| 単体表示 | `Rect(24, 88 - 1166, 688)` | `Rect(0, 0 - 1190, 700)` | `Rect(24, 0 - 1166, 700)` |
| split 表示 | `Rect(444, 88 - 1166, 688)` | `Rect(420, 0 - 1190, 700)` | `Rect(444, 0 - 1166, 700)` |

## 意味

- raw Surface は split 前後で `1190x700` のまま。
- `visibleArea` / `stableArea` は host 推奨の安全領域であり、描画不能領域ではない。
- MapView / 地図キャンバスは青枠(observed frame)いっぱいに描画する。
- ボタンやラベルなど安全領域に置きたい UI だけ、必要に応じて `stableArea` / host slot を参照する。

## 入力座標

タッチ座標は raw Surface 座標で届くため、OneNaviApp に渡す時は青枠の左上を原点に変換する。

```
appX = surfaceX - observedLeft
appY = surfaceY
```

host slot の外側かつ青枠の内側のタッチも OneNaviApp 側では有効入力として扱う。

## 残課題

- DHU 以外の head unit、解像度、DPI、split 比率で `horizontalSafetyInset` が左右対称とみなせるか確認する。
- 右側 split / media pane 配置差分でも青枠が実際の表示境界に一致するか確認する。
- 実装時は `visibleArea` 更新ごとに observed frame を再計算し、MapView / ComposeView の bounds と入力変換へ同じ値を使う。
