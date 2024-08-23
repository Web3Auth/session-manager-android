package com.web3auth.session_manager_android.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.encoders.Hex
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.Security
import javax.crypto.KeyGenerator

object KeyStoreManager {
    private const val Android_KEY_STORE = "AndroidKeyStore"
    private const val WEB3AUTH = "Web3Auth"
    const val SESSION_ID_TAG = "sessionId"
    private lateinit var sharedPreferences: EncryptedSharedPreferences

    init {
        setupBouncyCastle()
    }

    fun initializePreferences(context: Context) {
            val keyGenParameterSpec = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            sharedPreferences = EncryptedSharedPreferences.create(
                context,
                WEB3AUTH,
                keyGenParameterSpec,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
    }

    /**
     * Key generator to encrypt and decrypt data
     */
    fun getKeyGenerator() {
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Android_KEY_STORE)
        val keyGeneratorSpec = KeyGenParameterSpec.Builder(
            WEB3AUTH,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(keyGeneratorSpec)
        keyGenerator.generateKey()
    }

    /**
     * Store encrypted data into preferences
     */
    fun savePreferenceData(key: String, data: String) {
        sharedPreferences.edit().putString(key, data)?.apply()
    }

    /**
     * Retrieve decrypted data from preferences
     */
    fun getPreferencesData(key: String): String? {
        return sharedPreferences.getString(key, "")
    }

    /**
     * Delete All local storage
     */
    fun deletePreferencesData(key: String) {
        if (this::sharedPreferences.isInitialized) {
            sharedPreferences.edit().remove(key)?.apply()
        }
    }

    /**
     * Get Public key from sessionID
     */
    fun getPubKey(sessionId: String): String {
        val derivedECKeyPair: ECKeyPair = ECKeyPair.create(BigInteger(sessionId, 16))
        return derivedECKeyPair.publicKey.toString(16)
    }

    /**
     * Generate temporary private and public key that is used to secure receive shares
     */
    fun generateRandomSessionKey(): String {
        val tmpKey = Keys.createEcKeyPair()
        return tmpKey.privateKey.toString(16).padStart(64, '0')
    }

    /**
     * Initialize BouncyCastle for generation sessionId
     */
    private fun setupBouncyCastle() {
        val provider = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
            ?: // Web3j will set up the provider lazily when it's first used.
            return
        if (provider.javaClass == BouncyCastleProvider::class.java) {
            return
        }
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    fun randomBytes(len: Int): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(len)
        random.nextBytes(bytes)
        return bytes
    }

    /**
     * Generate Signature with privateKey and message
     */
    fun getECDSASignature(privateKey: BigInteger?, data: String): String {
        val derivedECKeyPair = ECKeyPair.create(privateKey)
        val hashedData = Hash.sha3(data.toByteArray(StandardCharsets.UTF_8))
        val signature = derivedECKeyPair.sign(hashedData)
        val v = ASN1EncodableVector()
        v.add(ASN1Integer(signature.r))
        v.add(ASN1Integer(signature.s))
        val der = DERSequence(v)
        val sigBytes = der.encoded
        return Hex.toHexString(sigBytes)
    }
}