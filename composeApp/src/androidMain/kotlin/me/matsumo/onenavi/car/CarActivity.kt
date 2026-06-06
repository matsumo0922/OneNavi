package me.matsumo.onenavi.car

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * STEP1a 検証用 CarActivity。
 *
 * Android Auto の parked-app(NATIVE_APP)経路で車 display に起動されることを狙う。
 * 第一段では OneNaviApp ではなく診断画面を出し、「車 display に出たか」「本物の MotionEvent が
 * 届くか」を切り分けて確認する。確認後に中身を OneNaviApp に差し替える。
 */
class CarActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val resolvedDisplayId = display?.displayId ?: -1
        Log.i(TAG, "onCreate: displayId=$resolvedDisplayId, displayName=${display?.name}")
        setContent {
            CarDiagnosticScreen(displayId = resolvedDisplayId)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "onResume: displayId=${display?.displayId}")
    }

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCar"
    }
}

@Composable
private fun CarDiagnosticScreen(
    displayId: Int,
    modifier: Modifier = Modifier,
) {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableStateOf("-") }
    var boxOffset by remember { mutableStateOf(IntOffset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(color = 0xFF102030))
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    tapCount += 1
                    lastTap = "${offset.x.roundToInt()}, ${offset.y.roundToInt()}"
                    Log.i("OneNaviCar", "TAP #$tapCount at $lastTap")
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "OneNavi CarActivity 診断",
                color = Color.White,
                fontSize = 28.sp,
            )
            Text(
                text = "displayId = $displayId",
                color = Color.White,
                fontSize = 20.sp,
            )
            Text(
                text = "tap count = $tapCount",
                color = Color.White,
                fontSize = 20.sp,
            )
            Text(
                text = "last tap = $lastTap",
                color = Color.White,
                fontSize = 20.sp,
            )
            Text(
                text = "↓ 箱をドラッグできれば本物タッチ",
                color = Color.White,
                fontSize = 16.sp,
            )
        }

        Box(
            modifier = Modifier
                .offset { boxOffset }
                .size(96.dp)
                .background(Color(color = 0xFFE0533D))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        boxOffset = IntOffset(
                            x = boxOffset.x + dragAmount.x.roundToInt(),
                            y = boxOffset.y + dragAmount.y.roundToInt(),
                        )
                    }
                },
        )
    }
}
