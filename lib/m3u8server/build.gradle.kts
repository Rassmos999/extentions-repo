plugins {
    alias(kei.plugins.library)
}

dependencies {
    // jitpack's "-SNAPSHOT"/master builds of NanoHTTPD 3.x no longer resolve (pom 404 /
    // version mismatch), so use the 2.3.1 release (fi.iki.elonen API), same as animepahe.
    implementation("com.github.NanoHttpd.nanohttpd:nanohttpd:nanohttpd-project-2.3.1")
}
