package me.matsumo.onenavi.core.ui.animation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

/**
 * Material3 Design ガイドラインに準拠したナビゲーショントランジション
 *
 * @see <a href="https://m3.material.io/styles/motion/easing-and-duration/tokens-specs">Material3 Motion</a>
 */
object NavigationTransitions {
    // Material3 Emphasized Easing curves
    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    // Duration (ms)
    private const val ENTER_DURATION = 400
    private const val EXIT_DURATION = 200
    private const val FADE_DURATION = 150

    // スライド量 (画面幅の25%)
    private const val SLIDE_DISTANCE_RATIO = 0.25f

    /**
     * 前進遷移（次の画面へ進む）
     * 新画面: 右からスライドイン + フェードイン
     * 古い画面: 左へスライドアウト + フェードアウト
     */
    val forwardTransition: ContentTransform
        get() = slideInHorizontally(
            animationSpec = tween(
                durationMillis = ENTER_DURATION,
                easing = EmphasizedDecelerate,
            ),
            initialOffsetX = { (it * SLIDE_DISTANCE_RATIO).toInt() },
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = FADE_DURATION,
                easing = EmphasizedDecelerate,
            ),
        ) togetherWith slideOutHorizontally(
            animationSpec = tween(
                durationMillis = EXIT_DURATION,
                easing = EmphasizedAccelerate,
            ),
            targetOffsetX = { -(it * SLIDE_DISTANCE_RATIO).toInt() },
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = FADE_DURATION,
                easing = EmphasizedAccelerate,
            ),
        )

    /**
     * 戻り遷移（前の画面へ戻る）
     * 新画面（戻り先）: 左からスライドイン + フェードイン
     * 古い画面: 右へスライドアウト + フェードアウト
     */
    val backwardTransition: ContentTransform
        get() = slideInHorizontally(
            animationSpec = tween(
                durationMillis = ENTER_DURATION,
                easing = EmphasizedDecelerate,
            ),
            initialOffsetX = { -(it * SLIDE_DISTANCE_RATIO).toInt() },
        ) + fadeIn(
            animationSpec = tween(
                durationMillis = FADE_DURATION,
                easing = EmphasizedDecelerate,
            ),
        ) togetherWith slideOutHorizontally(
            animationSpec = tween(
                durationMillis = EXIT_DURATION,
                easing = EmphasizedAccelerate,
            ),
            targetOffsetX = { (it * SLIDE_DISTANCE_RATIO).toInt() },
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = FADE_DURATION,
                easing = EmphasizedAccelerate,
            ),
        )
}
