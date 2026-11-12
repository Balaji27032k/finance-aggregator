package com.personal.financecollector.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.profile.MoneyProfile
import com.personal.financecollector.profile.ProfileApi
import com.personal.financecollector.profile.ProfileOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileState(
    val profile: MoneyProfile = MoneyProfile(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val dirty: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/** The person's details. The server holds the saved copy; edits stay here until Save. */
class ProfileViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as FinanceApp).container
    private val api = ProfileApi(container.http)

    private val _state = MutableStateFlow(ProfileState())
    val state: StateFlow<ProfileState> = _state

    private suspend fun connection(): Pair<String, String>? {
        val config = container.db.configDao()
        val base = if (config.get(ConfigKeys.PAIRED) == "true") config.get(ConfigKeys.BACKEND_URL) else null
        val token = container.credentials.token()
        return if (base == null || token == null) null else base to token
    }

    /** Loads the saved profile once; edits already made are never overwritten. */
    fun load(force: Boolean = false) {
        val s = _state.value
        if (s.loading || s.saving || (s.loaded && !force) || s.dirty) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val link = connection()
            if (link == null) {
                _state.update { it.copy(loading = false, error = "Pair this phone with the server first (Health tab) to save your profile.") }
                return@launch
            }
            when (val outcome = api.get(link.first, link.second)) {
                is ProfileOutcome.Ok -> _state.update { it.copy(profile = outcome.profile, loaded = true, loading = false) }
                ProfileOutcome.Unauthorized -> _state.update { it.copy(loading = false, error = "This phone is no longer paired. Pair it again in the Health tab.") }
                is ProfileOutcome.Failed -> _state.update { it.copy(loading = false, error = "Could not load your profile: ${outcome.reason}.") }
            }
        }
    }

    fun edit(change: (MoneyProfile) -> MoneyProfile) {
        _state.update { it.copy(profile = change(it.profile), dirty = true, message = null) }
    }

    fun save() {
        val s = _state.value
        if (s.saving || !s.dirty) return
        if (s.profile.age != null && s.profile.age !in 14..100) {
            _state.update { it.copy(error = "Age must be between 14 and 100.") }
            return
        }
        _state.update { it.copy(saving = true, error = null, message = null) }
        viewModelScope.launch {
            val link = connection()
            if (link == null) {
                _state.update { it.copy(saving = false, error = "Pair this phone with the server first (Health tab).") }
                return@launch
            }
            when (val outcome = api.put(link.first, link.second, s.profile)) {
                is ProfileOutcome.Ok -> _state.update { it.copy(profile = outcome.profile, saving = false, dirty = false, loaded = true, message = "Saved. The coach will use this.") }
                ProfileOutcome.Unauthorized -> _state.update { it.copy(saving = false, error = "This phone is no longer paired. Pair it again in the Health tab.") }
                is ProfileOutcome.Failed -> _state.update { it.copy(saving = false, error = "Could not save: ${outcome.reason}.") }
            }
        }
    }
}
