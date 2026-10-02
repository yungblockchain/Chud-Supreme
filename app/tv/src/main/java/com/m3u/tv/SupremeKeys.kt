package com.m3u.tv

/**
 * Keys the owner asked to ship in this build. They are written into the encrypted
 * store on first launch, and a key typed later in Settings replaces them.
 * The Real-Debrid value is the token only. The sports key had been pasted on the end of it.
 */
internal object SupremeKeys {
    const val TMDB = "94cf789639ae0b2e06c65a9f2ccad10a"
    const val COINMARKETCAP = "0b906811cf3e4ec39bef56e2e69683a7"
    const val API_SPORTS = "6adb1a1f9ea8091f6f5fb16e4abc0fc5"
    const val REAL_DEBRID = "JH4W4WZ3FAKRMGDM7WHOKVDM4EFXIQZFTIUIZAOD326JEGQKZPHA"
    const val TORBOX = "0cc19b5a-61d0-4d08-811c-32cae57ffbdc"
}
