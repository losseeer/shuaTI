package com.shua.ti

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shua.ti.ui.AppViewModel
import com.shua.ti.ui.UpdateUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机验证「检查更新」：走真实网络请求 GitHub Releases，
 * 断言状态机到达终态（有新版本 / 已最新 / 失败可见），并把实际结果打出来。
 *
 * 网络不可用时不视为测试失败（会得到 Failed，同样算终态），
 * 但日志里能看到具体原因——这是有意为之：更新检查依赖外网，不能让它拖红 CI。
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckTest {

    @Test
    fun check_update_reaches_terminal_state_on_device() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = AppViewModel(app)

        vm.checkUpdate()

        val state = withTimeout(30_000) {
            vm.state.first { it.update != null && it.update !is UpdateUi.Checking }
        }
        val update = state.update!!
        println("UPDATE_RESULT=$update")

        when (update) {
            is UpdateUi.Available -> {
                assertTrue(update.latestVersion.isNotBlank())
                assertTrue(update.downloadUrl.startsWith("https://"))
            }
            is UpdateUi.UpToDate -> Unit
            is UpdateUi.Failed -> assertTrue(update.message.isNotBlank())
            else -> error("仍在进行中：$update")
        }
    }
}
