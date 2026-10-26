package com.purposegate

import androidx.compose.runtime.MutableState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayExitTest {
    @Suppress("UNCHECKED_CAST")
    @Test fun unselectedWechatClearsThePreviousTargetsOverlayEvenWhenAlreadyRecordedAsCurrent() {
        val controller = Robolectric.buildService(PurposeAccessibilityService::class.java).create()
        val service = controller.get()
        val type = service.javaClass
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        try {
            PurposeGateStore.setSelectedPackages(service, setOf("target"))
            field("currentPackage").set(service, "com.tencent.mm")
            val owner = field("overlayOwnerPackage" + '$' + "delegate").get(service) as MutableState<String?>
            val state = field("state" + '$' + "delegate").get(service) as MutableState<Any?>
            owner.value = "target"
            val bar = Class.forName("com.purposegate.OverlayState" + '$' + "PurposeBar")
                .getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance("查资料")
            state.value = bar
            type.getDeclaredMethod("handleForegroundPackage", String::class.java)
                .apply { isAccessible = true }.invoke(service, "com.tencent.mm")
            assertNull(owner.value)
            assertNull(state.value)
            assertTrue((field("sessions").get(service) as Map<*, *>).containsKey("target"))
        } finally {
            controller.destroy()
        }
    }
}
