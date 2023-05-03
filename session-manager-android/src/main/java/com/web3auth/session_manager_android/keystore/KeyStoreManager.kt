package com.web3auth.session_manager_android.keystore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlin.text.Charsets.UTF_8

object KeyStoreManager {

    private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
    private const val Android_KEY_STORE = "AndroidKeyStore"
    private const val WEB3AUTH = "Web3Auth"
    const val IV_KEY = "ivKey"
    const val EPHEM_PUBLIC_Key = "ephemPublicKey"
    const val MAC = "mac"
    const val SESSION_ID = "sessionId"
    private lateinit var encryptedPairData: Pair<ByteArray, ByteArray>

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private lateinit var sharedPreferences: EncryptedSharedPreferences

    fun initializePreferences(context: Context) {
        try {
            sharedPreferences = EncryptedSharedPreferences.create(
                "Web3Auth",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
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
     * Method to encrypt data with key
     */
    fun encryptData(key: String, data: String) {
        sharedPreferences.edit().putString(key, data)?.apply()
        encryptedPairData = getEncryptedDataPair(data)
        encryptedPairData.second.toString(UTF_8)
    }

    /**
     * Key generator to encrypt/decrypt data
     */
    private fun getEncryptedDataPair(data: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getKey())

        val iv: ByteArray = cipher.iv
        val encryptedData = cipher.doFinal(data.toByteArray(UTF_8))
        return Pair(iv, encryptedData)
    }

    /**
     * Method to decrypt data with key
     */
    fun decryptData(key: String): String? {
        var result: String? = null
        try {
            val sharedPreferenceIds = sharedPreferences.all
            sharedPreferenceIds.forEach {
                if (it.key.contains(key)) {
                    result = sharedPreferences.getString(it.key, "")
                }
            }
            if (result == null) return null
            val encryptedPairData = result?.let { getEncryptedDataPair(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val keySpec = IvParameterSpec(encryptedPairData?.first)
            cipher.init(Cipher.DECRYPT_MODE, getKey(), keySpec)
            return cipher.doFinal(encryptedPairData?.second).toString(UTF_8)
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return result
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
        sharedPreferences.edit().remove(key)?.apply()
    }

    /**
     * Get key from KeyStore
     */
    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance(Android_KEY_STORE)
        keyStore.load(null)
        val secreteKeyEntry: KeyStore.SecretKeyEntry =
            keyStore.getEntry(WEB3AUTH, null) as KeyStore.SecretKeyEntry
        return secreteKeyEntry.secretKey
    }

    /**
     * Get Public key from sessionID
     */
    fun getPubKey(sessionId: String): String {
        val derivedECKeyPair: ECKeyPair = ECKeyPair.create(BigInteger(sessionId, 16))
        return derivedECKeyPair.publicKey.toString(16)
    }

    /**
     * Get Private key from sessionID
     */
    fun getPrivateKey(sessionId: String): String {
        val derivedECKeyPair: ECKeyPair = ECKeyPair.create(BigInteger(sessionId, 16))
        return derivedECKeyPair.privateKey.toString(16)
    }

    /**
     * Generate temporary private and public key that is used to secure receive shares
     */
    fun generateRandomSessionKey(): String {
        setupBouncyCastle()
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

    fun randomString(len: Int): String {
        val charSet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        charSet.length
        charSet.chars()
        val rnd = SecureRandom()
        val sb = StringBuilder(len)
        for (i in 0 until len) sb.append(charSet[rnd.nextInt(charSet.length)])
        return sb.toString()
    }

    /**
     * Generate Signature with privateKey and message
     */
    fun getECDSASignature(privateKey: BigInteger?, data: String): String? {
        val derivedECKeyPair = ECKeyPair.create(privateKey)
        val hashedData = Hash.sha3(data.toByteArray(StandardCharsets.UTF_8))
        val signature = derivedECKeyPair.sign(hashedData)
        val v = ASN1EncodableVector()
        v.add(ASN1Integer(signature.r))
        v.add(ASN1Integer(signature.s))
        val der = DERSequence(v)
        val sigBytes = der.encoded
        return convertByteToHexadecimal(sigBytes)
    }

    /**
     * convert byte array to hex string
     */
    @JvmStatic
    fun convertByteToHexadecimal(byteArray: ByteArray): String {
        var hex = ""
        // Iterating through each byte in the array
        for (i in byteArray) {
            hex += String.format("%02X", i)
        }
        return hex.lowercase(Locale.ROOT)
    }
}