package com.mapbox.toolkit.example.ui.main

data class MainUiState(val status: LiveStatus = LiveStatus.Idle, val error: String? = null)

enum class LiveStatus {
  Idle,
  Connecting,
  Connected,
  Closing,
}
