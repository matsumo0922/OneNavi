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

DHU split 表示の click callback は、青枠(observed frame)内のローカル座標として扱うと実表示と一致した。
`Presentation` の root 全体へ `MotionEvent` を投げる時は、青枠の左上を足して Surface 全体の座標へ戻す。

```
dispatchX = observedLeft + callbackX
dispatchY = observedTop + callbackY
```

host slot の外側かつ青枠の内側のタッチも OneNaviApp 側では有効入力として扱う。

### 2026-06-09 click 座標ずれの実測結果

split 表示で `visibleArea=Rect(444, 88 - 1166, 688)`、青枠が `Rect(420, 0 - 1190, 700)` の時、
`visibleLeft + callbackX` で click を注入すると実タップがマウスポインターより右へずれた。

実測のずれは約 `24px` で、これは `visibleLeft - observedLeft` と一致する。
つまり、click fallback / semantics click の dispatch 座標は host visible 起点ではなく、青枠起点で戻す必要がある。

```
誤: dispatchX = visibleLeft + callbackX
正: dispatchX = observedLeft + callbackX
```

実装上は `observedOffset` を split 時の click dispatch 座標として使い、Semantics と MotionEvent fallback の両方へ同じ座標を渡す。
debug overlay の赤丸は実際に採用した座標、黄色丸は `observedOffset` 候補を示す。

## 残課題

- DHU 以外の head unit、解像度、DPI、split 比率で `horizontalSafetyInset` が左右対称とみなせるか確認する。
- 右側 split / media pane 配置差分でも青枠が実際の表示境界に一致するか確認する。
- 実装時は `visibleArea` 更新ごとに observed frame を再計算し、MapView / ComposeView の bounds と入力変換へ同じ値を使う。
- 実機 head unit でも click callback が DHU と同じく observed frame ローカルとして扱えるか確認する。
