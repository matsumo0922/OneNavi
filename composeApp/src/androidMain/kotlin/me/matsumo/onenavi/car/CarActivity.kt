package me.matsumo.onenavi.car

import android.Manifest.permission
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.matsumo.onenavi.MainViewModel
import me.matsumo.onenavi.OneNaviApp
import me.matsumo.onenavi.core.common.car.CarDisplayState
import me.matsumo.onenavi.core.ui.theme.OneNaviTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Android Auto の parked-app(NATIVE_APP)経路で車 display に起動される Activity。
 *
 * MainActivity と同一プロセス内の独立した Activity であり、ViewModel は Activity スコープのため
 * 別インスタンスになる。一方で案内状態は Koin singleton(NewGuidanceManager 等)に載るためスマホ側と
 * 共有される。権限はアプリ全体スコープなので、スマホ側で許可済なら車側でも許可扱いになる
 * (車 display 上で権限ダイアログを出すと操作しづらいため、ここでは要求せず案内のみ表示する)。
 */
class CarActivity : ComponentActivity() {

    private val viewModel by viewModel<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarDisplayState.setOnCar(true)
        enableEdgeToEdge()
        setContent {
            val userData by viewModel.setting.collectAsStateWithLifecycle(null)

            userData?.let { setting ->
                if (hasRequiredPermissions()) {
                    OneNaviApp(
                        modifier = Modifier.fillMaxSize(),
                        setting = setting,
                    )
                } else {
                    OneNaviTheme(setting) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                modifier = Modifier.padding(24.dp),
                                text = "スマホ側で位置情報を許可してください",
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        CarDisplayState.setOnCar(false)
        super.onDestroy()
    }

    private fun hasRequiredPermissions(): Boolean {
        val result = ContextCompat.checkSelfPermission(this, permission.ACCESS_FINE_LOCATION)
        return result == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        /** logcat 抽出用タグ。 */
        const val TAG = "OneNaviCar"
    }
}
