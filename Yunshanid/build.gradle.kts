import com.lagradost.cloudstream3.gradle.CloudstreamExtension

apply(plugin = "com.android.library")
apply(plugin = "kotlin-android")
apply(plugin = "com.github.recloudstream")

configure<CloudstreamExtension> {
    // Kita gunakan "this." agar Gradle tahu ini properti milik Cloudstream, bukan milik Proyek
    this.id = "Yunshanid"
    this.name = "Yunshanid"
    this.pluginClass = "com.Yunshanid.YunshanidPlugin"
    this.description = "Dibuat oleh BetbetMiro untuk Yunshanid"
    this.authors = listOf("BetbetMiro")
}

dependencies {
    val cloudstreamVersion = "latest-SNAPSHOT"
    compileOnly("com.github.recloudstream:cloudstream3:$cloudstreamVersion")
}
