package com.dsh.castkit.sender.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.dsh.castkit.sender.Prefs
import com.dsh.castkit.sender.media.BrowserIconSize
import com.dsh.castkit.sender.media.VideoItem
import com.dsh.castkit.sender.media.VideoLibrary
import com.dsh.castkit.sender.media.VideoLibraryCache
import com.dsh.castkit.sender.media.VideoSortField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件 / 视频列表页的状态。
 *
 * 状态提升范围（Q13=C）：当前目录、每个目录的滚动位置、排序与显示设置、菜单开关、
 * 已加载条目、加载阶段全部搬进来，配置变更与页面重建后不再丢失。
 *
 * **不搬**进程级状态：`VideoLibraryCache`（跨页面复用）与 `VideoThumbnails` /
 * `VideoDurations`（带磁盘缓存的单例）仍在原处，VM 只读取它们。
 *
 * ★ 命名约定：凡是**同时**提供只读属性与 setter 的字段，一律写成
 * `private var xxxState by mutableStateOf(...)` + `val xxx get() = xxxState`。
 * 不能写成 `var xxx ... private set` 再加一个 `fun setXxx(...)`——两者会生成
 * 同一个 JVM 签名 `setXxx`，Kotlin 直接以 platform declaration clash 编译失败。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    /** 权限状态。只有 `refreshPermission()` / `onPermissionResult()` 会改它。 */
    var permission by mutableStateOf<LibraryPermissionState>(LibraryPermissionState.Denied)
        private set

    /** 是否拿到「所有文件访问」权限（`.nomedia` 目录需要它）。 */
    var allFilesGranted by mutableStateOf(VideoLibrary.canReadAllFiles(context))
        private set

    var currentPath by mutableStateOf(VideoLibrary.ROOT_PATH)
        private set

    private var sortFieldState by mutableStateOf(Prefs.browserSortField(context))
    val sortField: VideoSortField get() = sortFieldState

    private var sortAscState by mutableStateOf(Prefs.browserSortAsc(context))
    val sortAsc: Boolean get() = sortAscState

    /**
     * 预览大小。默认 `MEDIUM` = **文件夹 3 列 / 视频 2 列**，正好是 Q16 要求的默认值，
     * 所以不需要改 `Prefs` 的默认档；四档设置本身 1:1 保留。
     */
    private var iconSizeState by mutableStateOf(Prefs.browserIconSize(context))
    val iconSize: BrowserIconSize get() = iconSizeState

    var includeNoMedia by mutableStateOf(Prefs.browserIncludeNoMedia(context))
        private set

    private var showSortMenuState by mutableStateOf(false)
    val showSortMenu: Boolean get() = showSortMenuState

    private var showViewMenuState by mutableStateOf(false)
    val showViewMenu: Boolean get() = showViewMenuState

    private var showOverflowMenuState by mutableStateOf(false)
    val showOverflowMenu: Boolean get() = showOverflowMenuState

    /** 强制重扫计数：变化即触发一次"无视缓存年龄"的重扫。 */
    var refreshTick by mutableIntStateOf(0)
        private set

    private var lastRefreshTick = 0

    private var allVideos by mutableStateOf(VideoLibraryCache.merged ?: emptyList())

    /** 首次加载中（进程内缓存为空时）。 */
    private var loading by mutableStateOf(allVideos.isEmpty())

    /** 后台仍在补扫 `.nomedia`。 */
    var refining by mutableStateOf(false)
        private set

    /**
     * 每个文件夹各记一份滚动位置：否则在子文件夹滑到底、退回上级时上级也停在底部；
     * 同时也让"再次进入某个文件夹"回到上次的位置。
     */
    private val gridStates = mutableMapOf<String, LazyGridState>()

    init {
        refreshPermission()
    }

    fun gridState(): LazyGridState = gridStates.getOrPut(currentPath) { LazyGridState() }

    /** 页面状态：只表达内容，权限是独立维度。 */
    val state: LibraryUiState
        get() {
            if (loading) return LibraryUiState.Loading
            val videos = allVideos
            if (videos.isEmpty()) return LibraryUiState.Empty
            return LibraryUiState.Content(
                folders = VideoLibrary.sortFolders(
                    VideoLibrary.foldersIn(videos, currentPath),
                    sortFieldState,
                    sortAscState,
                ),
                videos = VideoLibrary.sortVideos(
                    VideoLibrary.videosIn(videos, currentPath),
                    sortFieldState,
                    sortAscState,
                ),
                refining = refining,
            )
        }

    fun refreshPermission() {
        permission = when {
            hasVideoPermission(context) && isPartialAccess(context) -> LibraryPermissionState.Partial
            hasVideoPermission(context) -> LibraryPermissionState.Granted
            else -> LibraryPermissionState.Denied
        }
        allFilesGranted = VideoLibrary.canReadAllFiles(context)
    }

    fun onPermissionResult(granted: Boolean) {
        refreshPermission()
        if (granted) requestLoad()
    }

    fun openFolder(path: String) {
        currentPath = path
    }

    fun navigateUp() {
        currentPath = currentPath.substringBeforeLast('/', "")
    }

    fun setShowSortMenu(value: Boolean) {
        showSortMenuState = value
    }

    fun setShowViewMenu(value: Boolean) {
        showViewMenuState = value
    }

    fun setShowOverflowMenu(value: Boolean) {
        showOverflowMenuState = value
    }

    fun setSortField(value: VideoSortField) {
        sortFieldState = value
        Prefs.setBrowserSortField(context, value)
    }

    fun setSortAsc(value: Boolean) {
        sortAscState = value
        Prefs.setBrowserSortAsc(context, value)
    }

    fun setIconSize(value: BrowserIconSize) {
        iconSizeState = value
        Prefs.setBrowserIconSize(context, value)
    }

    /**
     * 切换「显示 .nomedia 文件夹」。
     *
     * 没有「所有文件访问」权限时**不直接打开**，而是把决定权交回页面
     * （返回 false）让它去跳系统设置页——保持改造前的行为。
     *
     * @return true 表示状态已就地切换；false 表示需要页面去申请权限。
     */
    fun toggleIncludeNoMedia(): Boolean {
        if (includeNoMedia) {
            includeNoMedia = false
            Prefs.setBrowserIncludeNoMedia(context, false)
            return true
        }
        refreshPermission()
        if (!allFilesGranted) return false
        includeNoMedia = true
        Prefs.setBrowserIncludeNoMedia(context, true)
        return true
    }

    /** 请求一次强制重扫。 */
    fun requestLoad() {
        refreshTick++
    }

    /**
     * 两段式加载。由页面在 `LaunchedEffect(permission, includeNoMedia, refreshTick)` 里调用。
     *
     * 第一段：MediaStore（快）+ 文件系统补扫的磁盘缓存 → 立刻出内容；
     * 第二段：缓存过期或被强制刷新时，后台重扫，期间 `refining = true`。
     *
     * 补扫**不再**由「显示 .nomedia 文件夹」开关决定是否执行 —— 它同时负责把媒体库
     * 不收录的格式（`.vob`/`.rmvb`）捞回来，那些文件用户并没有藏，应该直接显示。
     * 开关只决定 `.nomedia` 子树那一桶要不要并进列表。
     */
    suspend fun load() {
        if (permission == LibraryPermissionState.Denied) return

        val forced = refreshTick != lastRefreshTick
        lastRefreshTick = refreshTick
        if (allVideos.isEmpty()) loading = true
        allFilesGranted = VideoLibrary.canReadAllFiles(context)

        val store = withContext(Dispatchers.IO) { VideoLibrary.loadFromMediaStore(context) }
        val cachedExtras = if (allFilesGranted) {
            withContext(Dispatchers.IO) { VideoLibrary.cachedExtraVideos(context) }
        } else {
            VideoLibrary.ExtraVideos()
        }
        allVideos = mergeVideos(store, extrasToAdd(cachedExtras))
        VideoLibraryCache.merged = allVideos
        loading = false

        val cacheStale =
            VideoLibrary.extraCacheAgeMs(context) > VideoLibrary.EXTRA_TTL_MS
        if (allFilesGranted && (forced || cacheStale)) {
            refining = true
            val extras = withContext(Dispatchers.IO) { VideoLibrary.scanExtraVideos(context) }
            if (!extras.isEmpty || forced) {
                withContext(Dispatchers.IO) { VideoLibrary.saveExtraCache(context, extras) }
            }
            allVideos = mergeVideos(store, extrasToAdd(extras))
            VideoLibraryCache.merged = allVideos
            refining = false
        }
    }

    /** 补扫结果里该并进列表的部分：媒体库漏掉的总是要，`.nomedia` 那一桶看开关。 */
    private fun extrasToAdd(extra: VideoLibrary.ExtraVideos): List<VideoItem> =
        if (includeNoMedia) extra.all else extra.libraryMissed

    /** 合并媒体库与补扫条目（按路径去重，媒体库优先）。 */
    private fun mergeVideos(store: List<VideoItem>, extras: List<VideoItem>): List<VideoItem> {
        if (extras.isEmpty()) return store
        val known = store.mapTo(HashSet()) { it.key }
        return store + extras.filter { it.key !in known }
    }

    /** 是否已拿到"能看到视频"的权限（Android 14+ 的"部分授权"也算，只是内容少）。 */
    private fun hasVideoPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (check(context, Manifest.permission.READ_MEDIA_VIDEO)) return true
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                check(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        }
        @Suppress("DEPRECATION")
        return check(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 14+ 只给了"部分视频"的授权。 */
    private fun isPartialAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return !check(context, Manifest.permission.READ_MEDIA_VIDEO) &&
            check(context, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
    }

    private fun check(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
