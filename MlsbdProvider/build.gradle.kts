cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/senkuboy0-cyber/MlsbdProvider")
    version = 6
    description = "JalshaMoviez - Bengali, Bollywood, Hollywood Movies & Web Series"
    authors = listOf("senkuboy0-cyber")
    language = "bn"
    tvTypes = listOf("Movie", "TvSeries", "Others")
}

android {
    namespace = "com.mlsbd"
}
