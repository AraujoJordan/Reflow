package com.example.reflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.reflow.viewmodel.PokemonViewModel
import io.github.araujojordan.LazyColumnPaginated

@Composable
fun App() {
    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Reflow Pokemon Sample") }
            )
            
            val viewModel: PokemonViewModel = viewModel { PokemonViewModel() }
            
            LazyColumnPaginated(
                paginatedFlow = viewModel.pokemonList,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
            ) { pokemon ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pokemon.name.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.h6
                        )
                    }
                }
            }
        }
    }
}
