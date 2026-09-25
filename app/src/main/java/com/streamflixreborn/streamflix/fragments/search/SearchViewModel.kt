package com.streamflixreborn.streamflix.fragments.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

// DEFINICIONES DE ESTADO Y RESULTADOS (Fuera de la clase para mejor acceso)
sealed class State {
    data object Searching : State()
    data object SearchingMore : State()
    data class SuccessSearching(val results: List<AppAdapter.Item>, val hasMore: Boolean) : State()
    data class FailedSearching(val error: Exception) : State()
    data object GlobalSearching : State()
    data class SuccessGlobalSearching(val providerResults: List<ProviderResult>) : State()
}

data class ProviderResult(
    val provider: Provider,
    val state: State,
) {
    sealed class State {
        data object Loading : State()
        data class Success(val results: List<AppAdapter.Item>) : State()
        data class Error(val error: Exception) : State()
    }
}


class SearchViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Searching)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = _state.transformLatest { currentState ->
        when (currentState) {
            is State.SuccessSearching -> {
                val movieIds = currentState.results.filterIsInstance<Movie>().map { it.id }
                val tvShowIds = currentState.results.filterIsInstance<TvShow>().map { it.id }

                if (movieIds.isEmpty() && tvShowIds.isEmpty()) {
                    emit(currentState)
                } else {
                    combine(
                        if (movieIds.isEmpty()) flowOf(emptyList<Movie>()) else database.movieDao().getByIds(movieIds),
                        if (tvShowIds.isEmpty()) flowOf(emptyList<TvShow>()) else database.tvShowDao().getByIds(tvShowIds)
                    ) { moviesDb, tvShowsDb ->
                        val moviesById = moviesDb.associateBy { it.id }
                        val tvShowsById = tvShowsDb.associateBy { it.id }

                        currentState.copy(
                            results = currentState.results.map { item ->
                                when (item) {
                                    is Movie -> moviesById[item.id]
                                        ?.takeIf { !item.isSame(it) }
                                        ?.let { item.copy().merge(it) }
                                        ?: item
                                    is TvShow -> tvShowsById[item.id]
                                        ?.takeIf { !item.isSame(it) }
                                        ?.let { item.copy().merge(it) }
                                        ?: item
                                    else -> item
                                }
                            }
                        )
                    }.collect { emit(it) }
                }
            }
            else -> emit(currentState)
        }
    }.flowOn(Dispatchers.IO)

    var query = ""
    private var page = 1

    init {
        search(query)
    }

    fun search(query: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("SearchViewModel", "Starting search for: '$query'")
        _state.value = State.Searching

        try {
            val provider = UserPreferences.currentProvider ?: throw Exception("No provider selected")
            val results = ParentalControlUtils.filterItems(provider.search(query).onEach { item ->
                when (item) {
                    is Movie -> item.providerName = provider.name
                    is TvShow -> item.providerName = provider.name
                }
            })
            Log.d("SearchViewModel", "Search finished. Results: ${results.size}")
            this@SearchViewModel.query = query
            page = 1
            _state.value = State.SuccessSearching(results, results.isNotEmpty())
        } catch (e: Exception) {
            Log.e("SearchViewModel", "search failed: ", e)
            _state.value = State.FailedSearching(e)
        }
    }

    fun loadMore() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessSearching) {
            _state.value = State.SearchingMore
            try {
                val provider = UserPreferences.currentProvider ?: throw Exception("No provider selected")
                val results = ParentalControlUtils.filterItems(
                    provider.search(query, page + 1).onEach { item ->
                        when (item) {
                            is Movie -> item.providerName = provider.name
                            is TvShow -> item.providerName = provider.name
                        }
                    }
                )
                val existingKeys = currentState.results
                    .asSequence()
                    .map { it.searchIdentityKey() }
                    .toHashSet()
                val newUniqueResults = results.filterNot { it.searchIdentityKey() in existingKeys }
                page += 1
                _state.value = State.SuccessSearching(
                    results = currentState.results + newUniqueResults,
                    hasMore = newUniqueResults.isNotEmpty(),
                )
            } catch (e: Exception) {
                Log.e("SearchViewModel", "loadMore: ", e)
                _state.value = State.FailedSearching(e)
            }
        }
    }

    // FUNCIÓN DE BÚSQUEDA GLOBAL AÑADIDA
    fun searchGlobal(query: String, currentLanguage: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.GlobalSearching)

        val targetProviders = Provider.providers.keys
            .filter { it.language == currentLanguage }
            .toList()

        if (targetProviders.isEmpty()) {
            _state.emit(State.SuccessGlobalSearching(emptyList()))
            return@launch
        }

        val initialResults = targetProviders.map { provider ->
            ProviderResult(provider, ProviderResult.State.Loading)
        }
        _state.emit(State.SuccessGlobalSearching(initialResults))

        val mutableResults = initialResults.toMutableList()

        val stateComparator = compareBy<ProviderResult> { providerResult ->
            when (val state = providerResult.state) {
                is ProviderResult.State.Success -> if (state.results.isNotEmpty()) 1 else 3
                is ProviderResult.State.Loading -> 2
                is ProviderResult.State.Error -> 4
            }
        }

        targetProviders.forEachIndexed { index, provider ->
            launch {
                try {
                    val results = ParentalControlUtils.filterItems(provider.search(query).onEach { item ->
                        // ========= ¡AQUÍ ESTÁ LA MAGIA! =========
                        // Le ponemos el sello a cada resultado
                        when (item) {
                            is Movie -> item.providerName = provider.name
                            is TvShow -> item.providerName = provider.name
                        }
                        // =======================================
                    })
                    mutableResults[index] = ProviderResult(provider, ProviderResult.State.Success(results))
                } catch (e: Exception) {
                    Log.e("SearchViewModel", "searchGlobal for ${provider.name}: ", e)
                    mutableResults[index] = ProviderResult(provider, ProviderResult.State.Error(e))
                }

                _state.emit(State.SuccessGlobalSearching(mutableResults.sortedWith(stateComparator)))
            }
        }
    }
}

private fun AppAdapter.Item.searchIdentityKey(): String = when (this) {
    is Movie -> "movie:$id"
    is TvShow -> "tvshow:$id"
    else -> "${this::class.java.name}:${hashCode()}"
}
