package com.ideasdeveloper.bms.server.outbound.monday

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Ayudas para leer las respuestas JSON de monday sin lanzar: lo que falta o no tiene la forma esperada queda vacio. */
fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

fun JsonElement?.asArr(): List<JsonElement> = (this as? JsonArray).orEmpty()

fun JsonElement?.asStr(): String = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim().orEmpty()
