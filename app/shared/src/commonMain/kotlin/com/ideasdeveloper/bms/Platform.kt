package com.ideasdeveloper.bms

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform