package com.web3auth.session_manager_android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.os.postDelayed
import com.google.gson.GsonBuilder
import com.web3auth.session_manager_android.api.ApiHelper
import com.web3auth.session_manager_android.api.Web3AuthApi
import com.web3auth.session_manager_android.keystore.KeyStoreManager
import com.web3auth.session_manager_android.models.SessionRequestBody
import com.web3auth.session_manager_android.types.*
import com.web3auth.session_manager_android.types.Base64.encodeBytes
import java8.util.concurrent.CompletableFuture
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.*
import kotlin.math.min

class SessionManager(context: Context) {

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val web3AuthApi = ApiHelper.getInstance().create(Web3AuthApi::class.java)
    private val mContext = context

    private var createSessionResponseCompletableFuture: CompletableFuture<String> =
        CompletableFuture()
    private var sessionCompletableFuture: CompletableFuture<String> = CompletableFuture()
    private var invalidateSessionCompletableFuture: CompletableFuture<Boolean> = CompletableFuture()

    init {
        KeyStoreManager.initializePreferences(context)
        initiateKeyStoreManager()
    }

    private fun initiateKeyStoreManager() {
        KeyStoreManager.getKeyGenerator()
    }

    fun saveSessionId(sessionId: String) {
        if (sessionId.isNotEmpty()) {
            KeyStoreManager.savePreferenceData(
                KeyStoreManager.SESSION_ID,
                sessionId
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @OptIn(DelicateCoroutinesApi::class)
    fun createSession(data: String, sessionTime: Long): CompletableFuture<String> {
        val newSessionKey = KeyStoreManager.generateRandomSessionKey()
        if (ApiHelper.isNetworkAvailable(mContext)) {
            try {
                val ephemKey = "04" + KeyStoreManager.getPubKey(newSessionKey)
                val ivKey = KeyStoreManager.randomString(32)
                val aes256cbc = AES256CBC(
                    newSessionKey,
                    ephemKey,
                    ivKey
                )

                val encryptedData = aes256cbc.encrypt(data.toByteArray(StandardCharsets.UTF_8))
                val mac = aes256cbc.macKey
                val encryptedMetadata = ShareMetadata(ivKey, ephemKey, encryptedData, mac)
                val gsonData = gson.toJson(encryptedMetadata)

                GlobalScope.launch {
                    val result = web3AuthApi.createSession(
                        SessionRequestBody(
                            key = "04".plus(KeyStoreManager.getPubKey(sessionId = newSessionKey)),
                            data = gsonData,
                            signature = KeyStoreManager.getECDSASignature(
                                BigInteger(newSessionKey, 16),
                                gsonData
                            ),
                            timeout = min(sessionTime, 7 * 86400)
                        )
                    )
                    if (result.isSuccessful) {
                        Handler(Looper.getMainLooper()).postDelayed(10) {
                            KeyStoreManager.savePreferenceData(
                                KeyStoreManager.SESSION_ID,
                                newSessionKey
                            )
                            createSessionResponseCompletableFuture.complete(newSessionKey)
                        }
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed(10) {
                            invalidateSessionCompletableFuture.completeExceptionally(
                                Exception(
                                    SessionManagerError.getError(
                                        ErrorCode.SOMETHING_WENT_WRONG
                                    )
                                )
                            )
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                createSessionResponseCompletableFuture.completeExceptionally(ex)
            }
        } else {
            createSessionResponseCompletableFuture.completeExceptionally(
                Exception(
                    SessionManagerError.getError(ErrorCode.RUNTIME_ERROR)
                )
            )
        }
        return createSessionResponseCompletableFuture
    }

    /**
     * Authorize User session in order to avoid re-login
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun authorizeSession(fromOpenLogin: Boolean): CompletableFuture<String> {
        sessionCompletableFuture = CompletableFuture()
        val sessionId = KeyStoreManager.getPreferencesData(KeyStoreManager.SESSION_ID).toString()
        if (sessionId.isEmpty()) {
            sessionCompletableFuture.completeExceptionally(
                Exception(
                    SessionManagerError.getError(
                        ErrorCode.SESSIONID_NOT_FOUND
                    )
                )
            )
        }
        if (sessionId.isNotEmpty() && ApiHelper.isNetworkAvailable(mContext)) {
            val pubKey = "04".plus(KeyStoreManager.getPubKey(sessionId))
            GlobalScope.launch {
                try {
                    val result = web3AuthApi.authorizeSession(pubKey)
                    if (result.isSuccessful && result.body() != null) {
                        val messageObj = result.body()?.message?.let { JSONObject(it).toString() }
                        val shareMetadata: ShareMetadata = gson.fromJson(
                            messageObj,
                            ShareMetadata::class.java
                        )

                        KeyStoreManager.savePreferenceData(
                            KeyStoreManager.EPHEM_PUBLIC_Key,
                            shareMetadata.ephemPublicKey.toString()
                        )
                        KeyStoreManager.savePreferenceData(
                            KeyStoreManager.IV_KEY,
                            shareMetadata.iv.toString()
                        )
                        KeyStoreManager.savePreferenceData(
                            KeyStoreManager.MAC,
                            shareMetadata.mac.toString()
                        )

                        val aes256cbc = AES256CBC(
                            sessionId,
                            shareMetadata.ephemPublicKey,
                            shareMetadata.iv.toString()
                        )

                        val share: String = if(fromOpenLogin) {
                            val encryptedShareBytes =
                                AES256CBC.toByteArray(shareMetadata.ciphertext?.let { BigInteger(it, 16) })
                            aes256cbc.decrypt(encodeBytes(encryptedShareBytes))
                        } else {
                            aes256cbc.decrypt(shareMetadata.ciphertext)
                        }

                        Handler(Looper.getMainLooper()).postDelayed(10) {
                            sessionCompletableFuture.complete(share)
                        }
                    } else {
                        sessionCompletableFuture.completeExceptionally(
                            Exception(
                                SessionManagerError.getError(
                                    ErrorCode.SESSION_EXPIRED
                                )
                            )
                        )
                    }
                } catch (ex: Exception) {
                    ex.printStackTrace()
                    sessionCompletableFuture.completeExceptionally(
                        Exception(
                            SessionManagerError.getError(
                                ErrorCode.NOUSERFOUND
                            )
                        )
                    )
                }
            }
        } else {
            sessionCompletableFuture.completeExceptionally(
                Exception(
                    SessionManagerError.getError(
                        ErrorCode.RUNTIME_ERROR
                    )
                )
            )
        }
        return sessionCompletableFuture
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun invalidateSession(): CompletableFuture<Boolean> {
        invalidateSessionCompletableFuture = CompletableFuture()
        if (ApiHelper.isNetworkAvailable(mContext)) {
            try {
                val ephemKey =
                    KeyStoreManager.getPreferencesData(KeyStoreManager.EPHEM_PUBLIC_Key)
                val ivKey = KeyStoreManager.getPreferencesData(KeyStoreManager.IV_KEY)
                val mac = KeyStoreManager.getPreferencesData(KeyStoreManager.MAC)
                val sessionId =
                    KeyStoreManager.getPreferencesData(KeyStoreManager.SESSION_ID).toString()

                if (ephemKey.isNullOrEmpty() || ivKey.isNullOrEmpty() || sessionId.isEmpty() || mac.isNullOrEmpty()) {
                    invalidateSessionCompletableFuture.complete(false)
                }

                val aes256cbc = AES256CBC(
                    sessionId,
                    ephemKey,
                    ivKey.toString()
                )
                val encryptedData = aes256cbc.encrypt("".toByteArray(StandardCharsets.UTF_8))
                val encryptedMetadata = ShareMetadata(ivKey, ephemKey, encryptedData, mac)
                val gsonData = gson.toJson(encryptedMetadata)

                GlobalScope.launch {
                    val result = web3AuthApi.invalidateSession(
                        SessionRequestBody(
                            key = "04".plus(KeyStoreManager.getPubKey(sessionId = sessionId)),
                            data = gsonData,
                            signature = KeyStoreManager.getECDSASignature(
                                BigInteger(sessionId, 16),
                                gsonData
                            ),
                            timeout = 1
                        )
                    )
                    if (result.isSuccessful) {
                        KeyStoreManager.deletePreferencesData(KeyStoreManager.SESSION_ID)
                        Handler(Looper.getMainLooper()).postDelayed(10) {
                            invalidateSessionCompletableFuture.complete(true)
                        }
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed(10) {
                            invalidateSessionCompletableFuture.completeExceptionally(
                                Exception(
                                    SessionManagerError.getError(
                                        ErrorCode.SOMETHING_WENT_WRONG
                                    )
                                )
                            )
                        }
                    }
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
                invalidateSessionCompletableFuture.completeExceptionally(ex)
            }
        } else {
            invalidateSessionCompletableFuture.completeExceptionally(
                Exception(
                    SessionManagerError.getError(
                        ErrorCode.RUNTIME_ERROR
                    )
                )
            )
        }
        return invalidateSessionCompletableFuture
    }
}