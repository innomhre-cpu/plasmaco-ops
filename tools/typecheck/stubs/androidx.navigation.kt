@file:Suppress("unused", "UNUSED_PARAMETER", "PackageDirectoryMismatch")

package androidx.navigation

import android.os.Bundle
class NavDestination { val route: String? get() = TODO(); val id: Int get() = TODO() }
class NavGraph { val id: Int get() = TODO(); companion object { fun NavGraph.findStartDestination(): NavDestination = TODO() } }
class NavBackStackEntry { val destination: NavDestination get() = TODO(); val arguments: Bundle? get() = TODO() }
class NavOptionsBuilder {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false
    fun popUpTo(id: Int, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
    fun popUpTo(route: String, popUpToBuilder: PopUpToBuilder.() -> Unit = {}) {}
}
class PopUpToBuilder { var inclusive: Boolean = false; var saveState: Boolean = false }
open class NavController {
    val graph: NavGraph get() = TODO()
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) {}
    fun navigate(route: String) {}
    fun popBackStack(): Boolean = TODO()
}
class NavHostController : NavController()
abstract class NavType<T> {
    companion object {
        val StringType: NavType<String?> = TODO()
        val IntType: NavType<Int> = TODO()
        val BoolType: NavType<Boolean> = TODO()
        val LongType: NavType<Long> = TODO()
    }
}
class NamedNavArgument
class NavArgumentBuilder {
    var type: NavType<*> = TODO()
    var nullable: Boolean = false
    var defaultValue: Any? = null
}
fun navArgument(name: String, builder: NavArgumentBuilder.() -> Unit): NamedNavArgument = TODO()
class NavDeepLink
class NavGraphBuilder
