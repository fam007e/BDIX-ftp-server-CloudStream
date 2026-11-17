// use an integer for version numbers
version = 1

android {
    namespace = "com.redowan"
}

cloudstream {
    description = "Only works in Bangladesh. BDIX CTG Fun FTP file server (movie.ctgfun.com)"
    authors = listOf("Redowan", "Fam007e")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "Others",
    )
    language = "bn"

    iconUrl = "https://movie.ctgfun.com/icons/folder.gif"
}
