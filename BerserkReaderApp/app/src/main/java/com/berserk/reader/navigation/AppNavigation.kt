package com.berserk.reader.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.berserk.reader.data.MangaRepository
import com.berserk.reader.data.PreferencesManager
import com.berserk.reader.ui.screens.HomeScreen
import com.berserk.reader.ui.screens.ReaderScreen
import com.berserk.reader.ui.screens.VolumeDetailScreen

@Composable
fun AppNavigation(
    repository: MangaRepository,
    prefs: PreferencesManager
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        // HOME SCREEN
        composable("home") {
            HomeScreen(
                repository = repository,
                prefs = prefs,
                onVolumeClick = { volNum ->
                    navController.navigate("volume/$volNum")
                },
                onResumeClick = { slug, page ->
                    val encodedSlug = Uri.encode(slug)
                    navController.navigate("reader?slug=$encodedSlug&page=$page")
                }
            )
        }

        // VOLUME DETAIL SCREEN
        composable(
            route = "volume/{volumeNumber}",
            arguments = listOf(
                navArgument("volumeNumber") { type = NavType.IntType }
            )
        ) { backStackEntry ->
            val volNum = backStackEntry.arguments?.getInt("volumeNumber") ?: 1
            VolumeDetailScreen(
                volumeNumber = volNum,
                repository = repository,
                prefs = prefs,
                onBackClick = { navController.popBackStack() },
                onChapterClick = { slug ->
                    val encodedSlug = Uri.encode(slug)
                    navController.navigate("reader?slug=$encodedSlug&page=1")
                }
            )
        }

        // READER SCREEN (Uses query parameters so slashes in slug don't break NavDeepLink path matching)
        composable(
            route = "reader?slug={chapterSlug}&page={page}",
            arguments = listOf(
                navArgument("chapterSlug") {
                    type = NavType.StringType
                    defaultValue = "reader/000-01"
                },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 1
                }
            )
        ) { backStackEntry ->
            val rawSlug = backStackEntry.arguments?.getString("chapterSlug") ?: "reader/000-01"
            val slug = Uri.decode(rawSlug)
            val page = backStackEntry.arguments?.getInt("page") ?: 1

            ReaderScreen(
                chapterSlug = slug,
                initialPage = page,
                repository = repository,
                prefs = prefs,
                onBackClick = { navController.popBackStack() },
                onNavigateChapter = { nextSlug, startPage ->
                    val encodedNext = Uri.encode(nextSlug)
                    navController.navigate("reader?slug=$encodedNext&page=$startPage") {
                        popUpTo("reader?slug={chapterSlug}&page={page}") { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
