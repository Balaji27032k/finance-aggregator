package com.personal.financecollector.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.FinanceApp
import com.personal.financecollector.sync.AskOutcome
import com.personal.financecollector.sync.AskReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ChatMessage(val fromUser: Boolean, val text: String, val reply: AskReply? = null, val failed: Boolean = false)

/** The Ask AI chat. It lives only as long as the app process; nothing is stored. */
class AskViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as FinanceApp).container

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun clear() {
        if (!_busy.value) _messages.value = emptyList()
    }

    fun send(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _busy.value) return
        val history = _messages.value.filter { !it.failed }.takeLast(8).map { (if (it.fromUser) "user" else "assistant") to it.text }
        _messages.value = _messages.value + ChatMessage(true, q)
        _busy.value = true
        viewModelScope.launch {
            val config = container.db.configDao()
            val base = if (config.get(ConfigKeys.PAIRED) == "true") config.get(ConfigKeys.BACKEND_URL) else null
            val token = container.credentials.token()
            val reply = if (base == null || token == null) {
                ChatMessage(false, "Pair this phone with the server first (Health tab).", failed = true)
            } else {
                when (val outcome = container.api.ask(base, token, q, history)) {
                    is AskOutcome.Done -> ChatMessage(false, outcome.reply.answer, outcome.reply)
                    AskOutcome.Unauthorized -> ChatMessage(false, "This phone is no longer paired. Pair it again in the Health tab.", failed = true)
                    is AskOutcome.Failed -> ChatMessage(false, "Could not get an answer: ${outcome.reason}.", failed = true)
                }
            }
            _messages.value = _messages.value + reply
            _busy.value = false
        }
    }
}
