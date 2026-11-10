package com.personal.financecollector.database

enum class SourceType { NOTIFICATION, SMS, GMAIL, MANUAL }

enum class UploadStatus { PENDING, UPLOADING, SYNCED, RETRY, NEEDS_REVIEW, FAILED_PERMANENTLY }

enum class ParserStatus { UNPARSED, PARSED, NEEDS_REVIEW }
