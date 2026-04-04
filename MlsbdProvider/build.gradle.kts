cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/senkuboy0-cyber/MlsbdProvider")
    version = 1
    description = "MLSBD - The Largest Movie Link Store of Bangladesh"
    authors = listOf("senkuboy0-cyber")
    iconUrl = "https://mlsbd.co/wp-content/uploads/2023/01/cropped-MLSBD-LOGO-32x32.png"
    language = "bn"
    status = ExtensionStatus.Beta
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Others"
    )
}

android {
    namespace = "com.mlsbd"
}
