package com.BetbetMiro.idlix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class IdlixProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(IdlixProvider())

        registerExtractorAPI(IdlixHtmlExtractor())
        registerExtractorAPI(Pm21P2p())
        registerExtractorAPI(Dm21Embed())
        registerExtractorAPI(Dm21Upns())
        registerExtractorAPI(MePlayer())
        registerExtractorAPI(SerhMePlayer())
        registerExtractorAPI(FourMePlayer())
        registerExtractorAPI(MinocHinos())
        registerExtractorAPI(DingTezuni())
        registerExtractorAPI(DinTezuvio())
        registerExtractorAPI(Mivalyo())
        registerExtractorAPI(MovEarnPre())
        registerExtractorAPI(VeevTo())
        registerExtractorAPI(HgCloud())
        registerExtractorAPI(HgLink())
        registerExtractorAPI(LuluVdoo())
        registerExtractorAPI(Majorplay())
        registerExtractorAPI(E2eMajorplay())
    }
}