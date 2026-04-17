package com.example.reflow.api

import com.example.reflow.model.Pokemon
import com.example.reflow.model.PokemonResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class PokeApi {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    suspend fun fetchPokemon(limit: Int = 20, offset: Int = 0): List<Pokemon> {
        val response: PokemonResponse = client.get("https://pokeapi.co/api/v2/pokemon") {
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
        return response.results
    }
}
