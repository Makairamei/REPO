package com.sad25kag.animemovies

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeMoviesPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeMovies())
    }
}
