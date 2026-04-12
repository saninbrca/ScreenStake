package com.detox.app.domain.model

/**
 * Comprehensive list of adult content domains blocked when a challenge has
 * [Challenge.blockAdultContent] = true.
 *
 * Matching in [AppDetectionAccessibilityService] is substring-based so that
 * subdomains (e.g. "www.pornhub.com", "en.pornhub.com") are covered by a
 * single entry without needing explicit wildcard syntax.
 */
object AdultDomains {

    val BLOCKED_DOMAINS: Set<String> = setOf(
        // ── Major tube sites ──────────────────────────────────────────────────
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "xhamster.com",
        "redtube.com",
        "youporn.com",
        "tube8.com",
        "spankbang.com",
        "motherless.com",
        "porn.com",
        "sex.com",
        "gaymaletube.com",
        "boyfriendtv.com",
        "gayporn.com",

        // ── Creator / subscription platforms ──────────────────────────────────
        "onlyfans.com",
        "fansly.com",

        // ── Live cam sites ─────────────────────────────────────────────────────
        "chaturbate.com",
        "stripchat.com",
        "livejasmin.com",
        "cam4.com",
        "bongacams.com",
        "myfreecams.com",
        "flirt4free.com",
        "imlive.com",
        "streamate.com",
        "jerkmate.com",
        "xhamsterlive.com",
        "camsoda.com",

        // ── Random video chat (adult) ──────────────────────────────────────────
        "slutroulette.com",
        "dirtyroulette.com",
        "shagle.com",

        // ── Premium studios ────────────────────────────────────────────────────
        "brazzers.com",
        "bangbros.com",
        "nubiles.net",
        "realitykings.com",
        "mofos.com",
        "twistys.com",
        "naughtyamerica.com",
        "digitalplayground.com",
        "girlfriendsfilms.com",
        "wankz.com",
        "helixstudios.com",
        "corbinfisher.com",
        "familydick.com",
        "transangels.com",
        "shemale.xxx",
        "ladyboy.xxx",
        "xart.com",

        // ── Magazines / lifestyle adult ────────────────────────────────────────
        "playboy.com",
        "penthouse.com",
        "hustler.com",

        // ── Dating / hook-up ──────────────────────────────────────────────────
        "adultfriendfinder.com",
        "ashleymadison.com",

        // ── Hentai / animated ─────────────────────────────────────────────────
        "hentaihaven.xxx",
        "nhentai.net",
        "rule34.xxx",
        "gelbooru.com",
        "e621.net",
        "furaffinity.net",

        // ── Written erotica ────────────────────────────────────────────────────
        "literotica.com",
        "eroticstories.com",
        "sexstories.com"
    )
}
