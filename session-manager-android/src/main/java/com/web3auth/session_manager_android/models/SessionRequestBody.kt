package com.web3auth.session_manager_android.models

import androidx.annotation.Keep

@Keep
data class SessionRequestBody(
    val key: String,
    val data: String,
    val signature: String,
    val timeout: Long = 0L
)