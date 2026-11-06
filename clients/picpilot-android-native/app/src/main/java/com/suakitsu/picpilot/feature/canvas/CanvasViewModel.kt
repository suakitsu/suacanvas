package com.suakitsu.picpilot.feature.canvas

import androidx.lifecycle.ViewModel
import com.suakitsu.picpilot.core.security.SecureSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class CanvasViewModel @Inject constructor(
    sessionStore: SecureSessionStore,
) : ViewModel() {
    val token: String = sessionStore.currentToken()
}
