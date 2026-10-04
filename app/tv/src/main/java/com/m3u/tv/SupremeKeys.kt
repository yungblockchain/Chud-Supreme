package com.m3u.tv

/**
 * Keys the owner asked to ship in this build. They are written into the encrypted store on first
 * launch, and a key typed later in Settings replaces them.
 *
 * The GitHub token is not in the source: GitHub cancels any token it finds in a public repository
 * within seconds. It comes from the `CHUD_GITHUB_TOKEN` repository secret at build time
 * (empty in a build without it, in which case crash reports stay on the device until a token is
 * entered in Settings > Services).
 */
internal object SupremeKeys {
    const val TMDB = "94cf789639ae0b2e06c65a9f2ccad10a"
    const val COINMARKETCAP = "0b906811cf3e4ec39bef56e2e69683a7"
    const val API_SPORTS = "5b5223cffa017ab9e7da79cd28fc1a8b"
    const val YOUTUBE = "AIzaSyBYe6YBEM29lRXUoOd2MdtkIEWhiU6cQ48"
    const val GITHUB_REPO = "yungblockchain/Chud-Supreme"
    val GITHUB_TOKEN: String get() = BuildConfig.CHUD_GITHUB_TOKEN
}
