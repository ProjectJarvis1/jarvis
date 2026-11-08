package com.meta.wearable.dat.externalsampleapps.cameraaccess.memory

data class MemoryItem(
    val id: String,
    val capturedAt: String,
    val capturedAtDisplay: String,
    val text: String,
    val source: String,
    val userNote: String?,
    val aiInterpretation: String?,
    val peopleText: String?,
    val imageFilename: String?,
    val labels: List<String>,
)

data class MemoryUiState(
    val isLoading: Boolean = false,
    val query: String = "",
    val memories: List<MemoryItem> = emptyList(),
    /** True when [memories] came from a search, so the list keeps the server's relevance order. */
    val isSearchResult: Boolean = false,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
)
