package com.assist.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.assist.data.TaskMemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives the learned-tasks (recipes) browser (phase-12). */
@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: TaskMemoryRepository,
) : ViewModel() {

    val state: StateFlow<RecipesUiState> =
        repository.listRecipes()
            .map { RecipesReducer.reduce(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    /** The recipe whose markdown is being previewed, or null. */
    private val _viewing = MutableStateFlow<RecipeContent?>(null)
    val viewing: StateFlow<RecipeContent?> = _viewing.asStateFlow()

    fun viewContent(id: Long, title: String) {
        viewModelScope.launch {
            val content = repository.recipeContent(id) ?: "(recipe file missing)"
            _viewing.value = RecipeContent(title, content)
        }
    }

    fun dismissContent() {
        _viewing.value = null
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteRecipe(id) }
    }

    data class RecipeContent(val title: String, val markdown: String)
}
