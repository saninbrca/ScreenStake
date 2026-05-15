package com.detox.app.domain.model

enum class PartialBlockSection(
    val id: String,
    val appPackage: String,
    val displayName: String,
    val subRowDescription: String,
    val activityNames: List<String>,
    val viewIds: List<String>,
    val contentDescriptions: List<String>
) {
    INSTAGRAM_REELS(
        id = "instagram_reels",
        appPackage = "com.instagram.android",
        displayName = "Instagram Reels",
        subRowDescription = "Nur Reels geblockt",
        activityNames = listOf(
            "com.instagram.reels.activity.ReelsActivity",
            "com.instagram.mainactivity.ReelViewerFragment"
        ),
        viewIds = listOf(
            "com.instagram.android:id/clips_tab",
            "com.instagram.android:id/reels_tray_container",
            "com.instagram.android:id/clips_viewer_fragment_container"
        ),
        contentDescriptions = listOf("Reels", "Reel")
    ),
    YOUTUBE_SHORTS(
        id = "youtube_shorts",
        appPackage = "com.google.android.youtube",
        displayName = "YouTube Shorts",
        subRowDescription = "Nur Shorts geblockt",
        activityNames = listOf(
            "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"
        ),
        viewIds = listOf(
            "com.google.android.youtube:id/reel_player_page",
            "com.google.android.youtube:id/shorts_shelf",
            "com.google.android.youtube:id/shorts_pivot_item"
        ),
        contentDescriptions = listOf("Shorts", "Short")
    ),
    TIKTOK_FORYOU(
        id = "tiktok_foryou",
        appPackage = "com.zhiliaoapp.musically",
        displayName = "TikTok For You",
        subRowDescription = "Nur For You geblockt",
        activityNames = listOf(
            "com.ss.android.ugc.aweme.main.MainActivity"
        ),
        viewIds = listOf(
            "com.zhiliaoapp.musically:id/feed_tab_tv",
            "com.zhiliaoapp.musically:id/aweme_video_player"
        ),
        contentDescriptions = listOf("For You", "Für dich")
    ),
    FACEBOOK_REELS(
        id = "facebook_reels",
        appPackage = "com.facebook.katana",
        displayName = "Facebook Reels",
        subRowDescription = "Nur Reels geblockt",
        activityNames = listOf(
            "com.facebook.reels.player.ReelsPlayerActivity"
        ),
        viewIds = listOf(
            "com.facebook.katana:id/reels_video_container"
        ),
        contentDescriptions = listOf("Reels")
    ),
    TWITTER_FORYOU(
        id = "twitter_foryou",
        appPackage = "com.twitter.android",
        displayName = "Twitter For You",
        subRowDescription = "Nur For You geblockt",
        activityNames = emptyList(),
        viewIds = listOf(
            "com.twitter.android:id/for_you_tab",
            "com.twitter.android:id/recommended_tweet_container"
        ),
        contentDescriptions = listOf("For you", "Für dich")
    ),
    SNAPCHAT_SPOTLIGHT(
        id = "snapchat_spotlight",
        appPackage = "com.snapchat.android",
        displayName = "Snapchat Spotlight",
        subRowDescription = "Nur Spotlight geblockt",
        activityNames = listOf(
            "com.snapchat.android.screenshottaker.SpotlightActivity"
        ),
        viewIds = listOf(
            "com.snapchat.android:id/spotlight_feed_container"
        ),
        contentDescriptions = listOf("Spotlight")
    );

    companion object {
        fun fromId(id: String): PartialBlockSection? = values().find { it.id == id }

        /** Maps appPackage → list of sections for that package. */
        val BY_PACKAGE: Map<String, List<PartialBlockSection>> = values().groupBy { it.appPackage }

        /** All packages that have at least one partial section defined. */
        val SUPPORTED_PACKAGES: Set<String> = BY_PACKAGE.keys
    }
}
