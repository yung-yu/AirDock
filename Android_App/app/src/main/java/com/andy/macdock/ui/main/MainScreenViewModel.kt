package com.andy.macdock.ui.main

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.andy.macdock.MacAppInfo
import com.andy.macdock.NearbyService
import com.andy.macdock.data.DataRepository
import com.andy.macdock.data.DefaultDataRepository
import com.andy.macdock.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainScreenViewModel(dataRepository: DataRepository) : ViewModel() {
  val uiState: StateFlow<MainScreenUiState> =
    dataRepository.data
      .map<List<String>, MainScreenUiState>(::Success)
      .catch { emit(MainScreenUiState.Error(it)) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

  // Connection management states (MVVM encapsulation)
  var connectionStatus by mutableStateOf("Disconnected")
    private set
  var verificationPin by mutableStateOf<String?>(null)
    private set
  var showVerificationDialog by mutableStateOf(false)
  var verificationHandler by mutableStateOf<((Boolean) -> Unit)?>(null)
  var macApps by mutableStateOf<List<MacAppInfo>>(emptyList())
    private set

  private var nearbyService: NearbyService? = null

  fun initializeNearbyService(context: Context) {
    if (nearbyService == null) {
      nearbyService = NearbyService(
        context = context.applicationContext,
        onStatusChanged = { status -> connectionStatus = status },
        onVerificationRequired = { pin, handler ->
          verificationPin = pin
          verificationHandler = handler
          showVerificationDialog = true
        },
        onAppListReceived = { list -> macApps = list }
      )
    }
  }

  fun startDiscovery() {
    nearbyService?.startDiscovery()
  }

  fun stopDiscovery() {
    nearbyService?.stopDiscovery()
  }

  fun switchSpace(direction: String) {
    nearbyService?.switchSpace(direction)
  }

  fun openApp(bundleId: String) {
    nearbyService?.openApp(bundleId)
  }

  fun disconnect() {
    nearbyService?.disconnect()
  }

  fun unpairAll() {
    nearbyService?.unpairAll()
    macApps = emptyList()
    connectionStatus = "Disconnected"
  }

  companion object {
    val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
      @Suppress("UNCHECKED_CAST")
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainScreenViewModel(DefaultDataRepository()) as T
      }
    }
  }
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<String>) : MainScreenUiState
}
