package io.github.mangi.eta.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentNavigator] 用编译期显式的 [appRouteTypesForTest] 把返回栈条目还原成
 * [AppRoute]（release 下 permittedSubclasses 反射不可靠）。本测试对照 JVM 的
 * PermittedSubclasses 元数据，防止新增路由时漏登记显式表。
 */
class AgentNavigatorRouteTableTest {

    @Test
    fun `explicit route table matches sealed subclasses`() {
        val declared = AppRoute::class.java.permittedSubclasses
            .orEmpty()
            .map { it.kotlin }
            .sortedBy { it.simpleName }
        val registered = appRouteTypesForTest.sortedBy { it.simpleName }
        assertEquals(
            "AppRoute 新增/删除子类后未同步 AgentNavigator 的显式路由表",
            declared,
            registered,
        )
    }

    @Test
    fun `route pattern matching covers plain and parameterized routes`() {
        val entry = routePatternOf(AppRoute.Settings) {
            "io.github.mangi.eta.ui.navigation.AppRoute.Settings"
        }
        assertTrue(entry != null)

        val parameterized = routePatternOf(AppRoute.Assistants(picker = false)) {
            "io.github.mangi.eta.ui.navigation.AppRoute.Assistants/{picker}"
        }
        assertTrue(parameterized != null)

        assertTrue(routePatternOf(AppRoute.Home) { "not.a.Route" } == null)
    }
}
