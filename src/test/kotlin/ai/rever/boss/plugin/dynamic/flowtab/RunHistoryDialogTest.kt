package ai.rever.boss.plugin.dynamic.flowtab

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RunHistoryDialogTest {
    @Test
    fun `dialog leaves viewport margin on short windows`() {
        assertEquals(448.dp, runHistoryDialogMaxHeight(480.dp))
    }

    @Test
    fun `dialog height is capped on tall windows`() {
        assertEquals(560.dp, runHistoryDialogMaxHeight(900.dp))
    }

    @Test
    fun `dialog height never becomes negative`() {
        assertEquals(0.dp, runHistoryDialogMaxHeight(20.dp))
    }
}
