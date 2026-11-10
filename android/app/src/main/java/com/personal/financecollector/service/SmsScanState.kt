package com.personal.financecollector.service

enum class SmsScanState {
    NOT_STARTED, RUNNING, COMPLETE, FAILED;

    companion object {
        fun parse(value: String?): SmsScanState = entries.firstOrNull { it.name == value } ?: NOT_STARTED
    }
}
