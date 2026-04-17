package com.example.reflow.viewmodel

import androidx.lifecycle.ViewModel
import com.example.reflow.api.PokeApi
import com.example.reflow.model.Pokemon
import io.github.araujojordan.reflowPaginated

class PokemonViewModel : ViewModel() {
    private val api = PokeApi()

    val pokemonList = reflowPaginated<Pokemon> { page ->
        api.fetchPokemon(
            limit = page.pageSize,
            offset = page.number * page.pageSize
        )
    }
}
