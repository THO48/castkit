package com.dsh.castkit.sender.ui

import com.dsh.castkit.sender.media.FolderEntry
import com.dsh.castkit.sender.media.VideoItem

/**
 * 视频库权限状态。
 *
 * **它是前置条件，不是内容状态**，所以刻意不塞进 [LibraryUiState]——
 * 否则 `Loading / Empty / Content` 三个语义会被权限污染，"空"到底是没有视频
 * 还是没权限就分不清了（改造前就是这个状态：权限面板靠 `return@Column` 旁路，
 * 和内容分支混在同一个 `when` 里）。
 */
sealed interface LibraryPermissionState {
    /** 已拿到权限（Android 14 的"部分授权"也算能看，单独归到 [Partial]）。 */
    data object Granted : LibraryPermissionState

    /** Android 14+ 只授权了部分视频：能看，但只显示被允许的那些。 */
    data object Partial : LibraryPermissionState

    /** 没授权：必须走权限引导。 */
    data object Denied : LibraryPermissionState
}

/**
 * 文件/视频列表页的内容状态。
 *
 * [Content] 里带 `refining` 是这个页面的真实形态所要求的：它是**两段式加载**——
 * MediaStore 先出结果（快），`.nomedia` 全盘遍历在后台补（慢，见 README 的
 * "打开速度"一节）。一个纯四态的 `Loading/Success/Error/Empty` 表达不了
 * "**已经有内容了、但还在后台补扫**"，只能二选一：要么在补扫期间把内容藏起来
 * （体验倒退），要么假装已经加载完（用户看到条目数突然跳变）。
 *
 * 投屏页与播放页**不套这一套**：它们的 `CastPhase` / `LocalPlaybackState` 是
 * 协议状态机与播放器状态，塞进四态会丢掉"重连中"这类信息（Q14 的 B+C 决定）。
 */
sealed interface LibraryUiState {
    /** 首次加载中（进程内缓存为空时才会出现）。 */
    data object Loading : LibraryUiState

    /** 当前目录下确实什么都没有。 */
    data object Empty : LibraryUiState

    /**
     * @param folders 当前目录的直接子文件夹（已排序、已聚合子树统计）。
     * @param videos 当前目录直接包含的视频（已排序）。
     * @param refining 后台仍在补扫 `.nomedia` 目录。为 true 时列表可能还会变长。
     */
    data class Content(
        val folders: List<FolderEntry>,
        val videos: List<VideoItem>,
        val refining: Boolean,
    ) : LibraryUiState
}
