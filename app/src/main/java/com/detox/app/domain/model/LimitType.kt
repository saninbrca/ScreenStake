package com.detox.app.domain.model

enum class LimitType {
    TIME,
    SESSIONS,
    /** User picks a total daily time budget (e.g. 39 min). Each session deducts from it. */
    TIME_BUDGET
}
