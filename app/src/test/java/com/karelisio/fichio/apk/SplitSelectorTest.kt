package com.karelisio.fichio.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.karelisio.fichio.core.util.Strings

class SplitSelectorTest {
    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a", "armeabi")

    @Test fun classifiesCommonLayouts() {
        assertEquals(SplitKind.BASE, SplitSelector.classify("base.apk").kind)
        assertEquals(SplitKind.BASE, SplitSelector.classify("splits/base-master.apk").kind)
        SplitSelector.classify("split_config.arm64_v8a.apk").let { assertEquals(SplitKind.ABI, it.kind); assertEquals("arm64_v8a", it.qualifier) }
        SplitSelector.classify("config.xxhdpi.apk").let { assertEquals(SplitKind.DENSITY, it.kind); assertEquals("xxhdpi", it.qualifier) }
        SplitSelector.classify("split_config.fr.apk").let { assertEquals(SplitKind.LANGUAGE, it.kind); assertEquals("fr", it.qualifier) }
        SplitSelector.classify("splits/base-x86_64.apk").let { assertEquals(SplitKind.ABI, it.kind); assertEquals("x86_64", it.qualifier) }
        SplitSelector.classify("splits/base-en.apk").let { assertEquals(SplitKind.LANGUAGE, it.kind) }
        assertEquals(SplitKind.FEATURE, SplitSelector.classify("splits/camera-master.apk").kind)
        assertEquals(SplitKind.FEATURE, SplitSelector.classify("split_feature_maps.apk").kind)
        assertEquals(SplitKind.OTHER_CONFIG, SplitSelector.classify("split_config.astc.apk").kind)
    }

    @Test fun xapkBaseIsTheLargestPackageApk() {
        val all = SplitSelector.classifyAll(
            listOf("com.example.app.apk" to 40_000_000L, "config.arm64_v8a.apk" to 9_000_000L, "config.fr.apk" to 10_000L),
        )
        assertEquals("com.example.app.apk", all.single { it.kind == SplitKind.BASE }.entryName)
    }

    @Test fun selectsBestAbiDensityAndLanguages() {
        val splits = SplitSelector.classifyAll(
            listOf(
                "base.apk", "split_config.arm64_v8a.apk", "split_config.armeabi_v7a.apk", "split_config.x86_64.apk",
                "split_config.mdpi.apk", "split_config.xhdpi.apk", "split_config.xxhdpi.apk", "split_config.xxxhdpi.apk",
                "split_config.fr.apk", "split_config.en.apk", "split_config.de.apk", "split_feature.apk",
            ).map { it to 1L },
        )
        // 440 dpi → smallest bucket at or above: xxhdpi (480).
        val picked = SplitSelector.select(splits, arm64Device, 440, listOf("fr", "en")).map { it.entryName }.toSet()
        assertEquals(
            setOf("base.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk", "split_config.fr.apk", "split_config.en.apk", "split_feature.apk"),
            picked,
        )
    }

    @Test fun densityFallsBackToHighestAndAbiToNone() {
        val splits = SplitSelector.classifyAll(listOf("base.apk", "split_config.hdpi.apk", "split_config.xhdpi.apk", "split_config.x86.apk").map { it to 1L })
        val picked = SplitSelector.select(splits, arm64Device, 640, listOf("fr")).map { it.entryName }.toSet()
        assertEquals(setOf("base.apk", "split_config.xhdpi.apk"), picked)
    }

    @Test fun abiCompatibility() {
        assertTrue(SplitSelector.abiCompatible(emptyList(), arm64Device))
        assertTrue(SplitSelector.abiCompatible(listOf("armeabi-v7a"), arm64Device))
        assertTrue(SplitSelector.abiCompatible(listOf("arm64_v8a"), arm64Device))
        assertFalse(SplitSelector.abiCompatible(listOf("x86", "x86_64"), arm64Device))
        assertEquals(
            listOf("arm64-v8a", "x86"),
            SplitSelector.abisFromEntries(sequenceOf("lib/x86/liba.so", "lib/arm64-v8a/liba.so", "lib/arm64-v8a/libb.so", "classes.dex", "lib/readme.txt")),
        )
    }

    @Test fun signatureFormatting() {
        assertEquals("00:0A:FF:7F", ApkFormat.fingerprint(byteArrayOf(0, 10, -1, 127)))
        val fp = ApkFormat.fingerprint(ApkFormat.sha256("abc".toByteArray()))
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD", fp)
        assertEquals(95, fp.length)
        val lines = ApkFormat.fingerprintLines(fp)
        assertEquals(2, lines.size)
        assertEquals(fp, lines.joinToString(":"))
    }

    @Test fun exportFileNames() {
        assertEquals("Mon Appli_1.2.3.apk", ApkFormat.exportFileName("Mon Appli", "1.2.3", false))
        assertEquals("A_B_C_2.0 beta.apks", ApkFormat.exportFileName("A/B:C", "2.0 beta", true))
        assertEquals("Jeu_1_2.apk", ApkFormat.exportFileName("Jeu?", "1/2", false).replace("Jeu__", "Jeu_"))
        assertEquals("appli.apk", ApkFormat.exportFileName("  ", null, false))
    }

    @Test fun installFailureMessages() {
        assertTrue(ApkFormat.installFailureMessage(ApkFormat.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: x")== Strings.get(com.karelisio.fichio.R.string.apk_fail_signature))
        assertEquals(Strings.get(com.karelisio.fichio.R.string.apk_install_cancelled), ApkFormat.installFailureMessage(ApkFormat.STATUS_FAILURE_ABORTED, null))
        assertEquals(Strings.get(com.karelisio.fichio.R.string.apk_fail_storage), ApkFormat.installFailureMessage(ApkFormat.STATUS_FAILURE_STORAGE, ""))
    }
}
