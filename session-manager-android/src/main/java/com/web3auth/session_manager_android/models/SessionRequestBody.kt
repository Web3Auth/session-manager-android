package com.web3auth.session_manager_android.models

import androidx.annotation.Keep

@Keep
data class SessionRequestBody(
    val key: String? = null,
    val data: String? = null,
    val signature: String? = null,
    val timeout: Long = 0L
)