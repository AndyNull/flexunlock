package com.flexunlock.dexlsp.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDisplayProfileConfigTest {
    @Test
    fun roundTripSortsAndDropsDefaultProfiles() {
        val profiles = listOf(
            AppDisplayProfile(
                packageName = "com.example.popup",
                windowMode = AppWindowMode.POPUP,
                popupWidthPercent = 70,
                popupHeightPercent = 64
            ),
            AppDisplayProfile(packageName = "com.example.default"),
            AppDisplayProfile(packageName = "com.example.compact", fullscreenPercent = 90)
        )

        assertEquals(
            mapOf(
                "com.example.compact" to AppDisplayProfile(
                    packageName = "com.example.compact",
                    fullscreenPercent = 90
                ),
                "com.example.popup" to AppDisplayProfile(
                    packageName = "com.example.popup",
                    windowMode = AppWindowMode.POPUP,
                    popupWidthPercent = 70,
                    popupHeightPercent = 64
                )
            ),
            AppDisplayProfileConfig.decode(AppDisplayProfileConfig.encode(profiles))
        )
    }

    @Test
    fun globalDefaultProfileIsPreservedWhileAppDefaultIsDropped() {
        val global = AppDisplayProfile(
            packageName = AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME
        )
        val decoded = AppDisplayProfileConfig.decode(
            AppDisplayProfileConfig.encode(
                listOf(global, AppDisplayProfile(packageName = "com.example.default"))
            )
        )

        assertEquals(global, decoded?.get(AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME))
        assertNull(decoded?.get("com.example.default"))
    }

    @Test
    fun legacyPresetsMigrateToPercentages() {
        val decoded = AppDisplayProfileConfig.decode(
            "v1\n" +
                "com.example.more|MORE_CONTENT|FULLSCREEN|MEDIUM\n" +
                "com.example.popup|DEFAULT|POPUP|LARGE"
        )

        assertEquals(80, decoded?.get("com.example.more")?.fullscreenPercent)
        assertEquals(92, decoded?.get("com.example.popup")?.popupWidthPercent)
        assertEquals(88, decoded?.get("com.example.popup")?.popupHeightPercent)
    }

    @Test
    fun globalFallbackProfileRoundTripsWithoutBecomingAnAppPackage() {
        val global = AppDisplayProfile(
            packageName = AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME,
            fullscreenPercent = 85
        )

        assertEquals(
            global,
            AppDisplayProfileConfig.decode(AppDisplayProfileConfig.encode(listOf(global)))
                ?.get(AppDisplayProfileConfig.GLOBAL_PROFILE_PACKAGE_NAME)
        )
    }

    @Test
    fun invalidPercentagesAndDuplicatesAreRejected() {
        assertNull(AppDisplayProfileConfig.decode("v2\n-".replace("v2", "v3")))
        assertNull(AppDisplayProfileConfig.decode("v2\ncom.example.app|30|FULLSCREEN|82|76"))
        assertEquals(
            35,
            AppDisplayProfileConfig.decode("v2\ncom.example.app|35|FULLSCREEN|82|76")
                ?.get("com.example.app")?.fullscreenPercent
        )
        assertNull(
            AppDisplayProfileConfig.decode(
                "v2\ncom.example.app|90|FULLSCREEN|82|76\n" +
                    "com.example.app|95|FULLSCREEN|82|76"
            )
        )
    }
}
