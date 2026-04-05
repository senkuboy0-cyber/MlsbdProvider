cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/senkuboy0-cyber/MlsbdProvider")
    version = 4
    description = "Fojik - Movies, Anime, Dual Audio & More"
    authors = listOf("senkuboy0-cyber")
    language = "en"
    tvTypes = listOf("Movie", "TvSeries", "Anime", "Others")
}

android {
    namespace = "com.mlsbd"
}
