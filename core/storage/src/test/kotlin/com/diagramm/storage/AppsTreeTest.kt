package com.diagramm.storage

import com.diagramm.model.NodeTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppsTreeTest {
    private val labels = AppsTreeLabels("Apps", "Code", "Data", "Cache", "App cache")

    private val apps = listOf(
        AppUsage("com.big", "Big", isSystem = false, codeBytes = 500, dataBytes = 300, cacheBytes = 200),
        AppUsage("com.small", "Small", isSystem = false, codeBytes = 50, dataBytes = 0, cacheBytes = 0),
        AppUsage("com.sys", "System", isSystem = true, codeBytes = 700, dataBytes = 10, cacheBytes = 5),
        AppUsage("com.empty", "Empty", isSystem = false, codeBytes = 0, dataBytes = 0, cacheBytes = 0),
        AppUsage("com.noname", "", isSystem = false, codeBytes = 1, dataBytes = 0, cacheBytes = 0),
    )

    @Test
    fun `apps are sorted by total size, empty ones dropped`() {
        val tree = AppsTree.build(apps, labels)
        assertEquals(listOf("Big", "System", "Small", "com.noname"), tree.children.map { it.name })
        assertEquals(715 + 1000 + 50 + 1, tree.size)
        assertEquals("Apps", tree.name)
    }

    @Test
    fun `each app splits into code data and cache, zero parts omitted`() {
        val tree = AppsTree.build(apps, labels)
        val big = tree.findById("app:com.big")!!
        assertEquals(listOf("Code", "Data", "Cache"), big.children.map { it.name })
        assertEquals(listOf(500L, 300L, 200L), big.children.map { it.size })
        assertEquals(listOf("Code"), tree.findById("app:com.small")!!.children.map { it.name })
    }

    @Test
    fun `tags mark components and system apps`() {
        val tree = AppsTree.build(apps, labels)
        assertEquals(NodeTags.SYSTEM_APP, tree.findById("app:com.sys")!!.tag)
        assertNull(tree.findById("app:com.big")!!.tag)
        assertEquals(NodeTags.COMPONENT, tree.findById("app:com.big#cache")!!.tag)
        assertTrue(tree.findById("app:com.big")!!.isCollectible)
        assertTrue(!tree.findById("app:com.sys")!!.isCollectible)
        assertTrue(!tree.findById("app:com.big#cache")!!.isCollectible)
    }

    @Test
    fun `package name is recovered from any node id`() {
        assertEquals("com.big", AppsTree.packageOf("app:com.big"))
        assertEquals("com.big", AppsTree.packageOf("app:com.big#data"))
        assertNull(AppsTree.packageOf("/storage/emulated/0/x"))
        assertNull(AppsTree.packageOf("apps:root"))
    }

    @Test
    fun `cache tree is sized by cache only and keeps system apps`() {
        val cache = AppsTree.buildCache(apps, labels)
        assertEquals("App cache", cache.name)
        assertEquals(listOf("Big", "System"), cache.children.map { it.name })
        assertEquals(listOf(200L, 5L), cache.children.map { it.size })
        assertTrue(cache.children.all { it.isFile && it.isCollectible })
        assertEquals("com.big", AppsTree.packageOf(cache.children[0].id))
    }

    @Test
    fun `usage list helpers`() {
        val big = apps.first { it.packageName == "com.big" }
        val cleared = AppsTree.withReplaced(apps, big.copy(cacheBytes = 0))
        assertEquals(0L, cleared.first { it.packageName == "com.big" }.cacheBytes)
        assertEquals(300L, cleared.first { it.packageName == "com.big" }.dataBytes)
        assertEquals(5L, cleared.first { it.packageName == "com.sys" }.cacheBytes)
        assertEquals(listOf("com.small", "com.sys", "com.empty", "com.noname"),
            AppsTree.withoutPackages(apps, setOf("com.big")).map { it.packageName })
        assertEquals(listOf("System"), AppsTree.buildCache(cleared, labels).children.map { it.name })
    }
}
