package com.personal.financecollector.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.admin.AdminApi
import com.personal.financecollector.admin.AdminHealth
import com.personal.financecollector.admin.AdminJson
import com.personal.financecollector.admin.AdminResult
import com.personal.financecollector.admin.AdminSummary
import com.personal.financecollector.admin.AdminTxnPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import java.time.LocalDate

data class Remote<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

/** Admin sign-in and the server-side views. Everything here lives only as long as the app process. */
class AdminViewModel(app: Application) : AndroidViewModel(app) {
    private val db = (app as FinanceApp).container.db
    private val api = AdminApi((app as FinanceApp).container.http)

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn
    val signingIn = MutableStateFlow(false)
    val loginError = MutableStateFlow<String?>(null)

    val summary = MutableStateFlow(Remote<AdminSummary>())
    val transactions = MutableStateFlow(Remote<AdminTxnPage>())
    val health = MutableStateFlow(Remote<AdminHealth>())

    private suspend fun baseUrl(): String? =
        if (db.configDao().get(ConfigKeys.PAIRED) == "true") db.configDao().get(ConfigKeys.BACKEND_URL) else null

    fun signIn(user: String, password: String) {
        viewModelScope.launch {
            val base = baseUrl()
            if (base == null) {
                loginError.value = "Pair this phone with the server first (Health tab)"
                return@launch
            }
            signingIn.value = true
            loginError.value = null
            when (val result = api.login(base, user.trim(), password)) {
                is AdminResult.Ok -> _signedIn.value = true
                is AdminResult.Failed -> loginError.value = result.message
                AdminResult.Unauthorized -> loginError.value = "Wrong user name or password"
            }
            signingIn.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            baseUrl()?.let { api.logout(it) }
            api.forget()
            _signedIn.value = false
            summary.value = Remote()
            transactions.value = Remote()
            health.value = Remote()
        }
    }

    private fun sessionEnded() {
        api.forget()
        _signedIn.value = false
        loginError.value = "Your session ended. Sign in again"
    }

    fun loadSummary(from: LocalDate, to: LocalDate, comparison: Comparison) = load(
        summary, "/api/admin/summary",
        mapOf(
            "from" to from.toString(), "to" to to.toString(),
            "previousFrom" to comparison.from.toString(), "previousTo" to comparison.to.toString(),
        ),
    ) { AdminJson.summary(it, comparison.label) }

    fun loadHealth() = load(health, "/api/admin/health", emptyMap(), AdminJson::health)

    fun loadTransactions(
        from: LocalDate, to: LocalDate, query: String, direction: String?, category: String?, size: Int,
    ) = load(
        transactions, "/api/admin/transactions",
        mapOf(
            "from" to from.toString(), "to" to to.toString(), "q" to query.trim(), "direction" to direction,
            "category" to category, "size" to size.toString(),
        ),
        AdminJson::transactions,
    )

    private fun <T> load(target: MutableStateFlow<Remote<T>>, path: String, query: Map<String, String?>, parse: (String) -> T) {
        viewModelScope.launch {
            val base = baseUrl()
            if (base == null) {
                target.value = Remote(error = "Pair this phone with the server first (Health tab)")
                return@launch
            }
            target.value = target.value.copy(loading = true, error = null)
            when (val result = api.get(base, path, query)) {
                is AdminResult.Ok -> target.value = try {
                    Remote(data = parse(result.value))
                } catch (e: JSONException) {
                    Remote(error = "The server sent something the app could not read")
                } catch (e: java.time.DateTimeException) {
                    Remote(error = "The server sent something the app could not read")
                }
                is AdminResult.Failed -> target.value = target.value.copy(loading = false, error = result.message)
                AdminResult.Unauthorized -> {
                    target.value = Remote()
                    sessionEnded()
                }
            }
        }
    }
}
