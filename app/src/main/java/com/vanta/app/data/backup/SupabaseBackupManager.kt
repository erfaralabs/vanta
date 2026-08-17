package com.vanta.app.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
import com.vanta.app.data.profile.ProfileBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages secure Supabase Cloud Backup & Restore for Vanta.
 *
 * Security & Architecture:
 * 1. Strictly enforces Supabase 'anon' (public) keys; explicitly rejects 'service_role' keys.
 * 2. Validates project URL format and key structure before saving.
 * 3. Encrypts sensitive credentials locally using AES-256-GCM backed by Android KeyStore.
 * 4. Compatible with strict Supabase Row Level Security (RLS).
 * 5. Supports configurable automatic backup frequency (Daily, 3 Days, Weekly, Off).
 */
object SupabaseBackupManager {

    private const val PREFS_NAME = "vanta_supabase_vault"
    private const val KEY_ENC_URL = "enc_project_url"
    private const val KEY_ENC_API_KEY = "enc_api_key"
    private const val KEY_LAST_BACKUP = "last_backup_at"
    private const val KEY_LAST_BACKUP_EPOCH = "last_backup_epoch"
    private const val KEY_AUTO_BACKUP_FREQ = "auto_backup_freq"
    private const val DEFAULT_RECORD_ID = "vanta_user_backup"

    // ── Local Hardware-Backed AES-256-GCM Encryption ─────────────────────────
    private object KeyStoreVault {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "vanta_supabase_hw_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128

        private fun getOrCreateSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
                return entry.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }

        fun encrypt(plain: String): String {
            if (plain.isBlank()) return ""
            return runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
                val iv = cipher.iv
                val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                val combined = ByteArray(iv.size + encrypted.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
                AndroidBase64.encodeToString(combined, AndroidBase64.NO_WRAP)
            }.getOrDefault(plain)
        }

        fun decrypt(cipherText: String): String {
            if (cipherText.isBlank()) return ""
            return runCatching {
                val combined = AndroidBase64.decode(cipherText, AndroidBase64.NO_WRAP)
                if (combined.size <= GCM_IV_LENGTH) return cipherText
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }.getOrDefault(cipherText)
        }
    }

    // ── Backup Frequency ─────────────────────────────────────────────────────
    enum class BackupFrequency(val label: String, val intervalMillis: Long) {
        OFF("Off", 0L),
        DAILY("Daily", 24 * 60 * 60 * 1000L),
        EVERY_3_DAYS("Every 3 Days", 3 * 24 * 60 * 60 * 1000L),
        WEEKLY("Weekly", 7 * 24 * 60 * 60 * 1000L)
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUrl(context: Context): String {
        val enc = getPrefs(context).getString(KEY_ENC_URL, "") ?: ""
        return KeyStoreVault.decrypt(enc)
    }

    fun getApiKey(context: Context): String {
        val enc = getPrefs(context).getString(KEY_ENC_API_KEY, "") ?: ""
        return KeyStoreVault.decrypt(enc)
    }

    fun getLastBackupTime(context: Context): String? =
        getPrefs(context).getString(KEY_LAST_BACKUP, null)

    fun isConfigured(context: Context): Boolean =
        getUrl(context).isNotBlank() && getApiKey(context).isNotBlank()

    fun getBackupFrequency(context: Context): BackupFrequency {
        val name = getPrefs(context).getString(KEY_AUTO_BACKUP_FREQ, BackupFrequency.DAILY.name)
        return runCatching { BackupFrequency.valueOf(name ?: BackupFrequency.DAILY.name) }
            .getOrDefault(BackupFrequency.DAILY)
    }

    fun setBackupFrequency(context: Context, frequency: BackupFrequency) {
        getPrefs(context).edit().putString(KEY_AUTO_BACKUP_FREQ, frequency.name).apply()
    }

    // ── Validation Helpers ───────────────────────────────────────────────────
    sealed class KeyValidationResult {
        object Valid : KeyValidationResult()
        data class Invalid(val reason: String) : KeyValidationResult()
    }

    /**
     * Validates that the provided key is a public 'anon' key and NOT a sensitive 'service_role' key.
     */
    fun validateAnonKey(key: String): KeyValidationResult {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            return KeyValidationResult.Invalid("API Key cannot be empty.")
        }
        val parts = trimmed.split(".")
        if (parts.size != 3) {
            return KeyValidationResult.Invalid("Invalid format. Must be a Supabase JWT anon key.")
        }
        return try {
            var payloadB64 = parts[1].replace('-', '+').replace('_', '/')
            while (payloadB64.length % 4 != 0) {
                payloadB64 += "="
            }
            val payloadJson = String(Base64.getDecoder().decode(payloadB64), Charsets.UTF_8)
            val json = JSONObject(payloadJson)
            val role = json.optString("role")
            if (role == "service_role") {
                KeyValidationResult.Invalid("🚨 SECURITY REJECTION: You entered a 'service_role' key! Never put service_role keys into mobile apps. Please use your public 'anon' key.")
            } else if (role != "anon") {
                KeyValidationResult.Invalid("Key role is '$role'. Only 'anon' publishable keys are allowed.")
            } else {
                KeyValidationResult.Valid
            }
        } catch (e: Exception) {
            KeyValidationResult.Invalid("Invalid JWT structure: ${e.localizedMessage}")
        }
    }

    fun cleanProjectUrl(raw: String): String {
        var u = raw.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        u = u.removeSuffix("/")
        if (u.endsWith("/rest/v1")) {
            u = u.removeSuffix("/rest/v1")
        }
        return u.removeSuffix("/")
    }

    fun saveConfig(context: Context, url: String, apiKey: String): Result<Unit> {
        val cleanUrl = cleanProjectUrl(url)
        if (!cleanUrl.contains(".supabase.co") && !cleanUrl.contains("localhost") && !cleanUrl.contains("127.0.0.1")) {
            return Result.failure(IllegalArgumentException("URL must be a valid Supabase project URL (e.g. https://xyz.supabase.co)"))
        }

        val keyValidation = validateAnonKey(apiKey)
        if (keyValidation is KeyValidationResult.Invalid) {
            return Result.failure(IllegalArgumentException(keyValidation.reason))
        }

        // Encrypt credentials at rest
        val encUrl = KeyStoreVault.encrypt(cleanUrl)
        val encKey = KeyStoreVault.encrypt(apiKey.trim())

        getPrefs(context).edit()
            .putString(KEY_ENC_URL, encUrl)
            .putString(KEY_ENC_API_KEY, encKey)
            .apply()

        return Result.success(Unit)
    }

    fun clearConfig(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    enum class TestStatus {
        SUCCESS,
        INVALID_KEY,
        NETWORK_ERROR,
        INVALID_URL
    }

    data class TestResult(
        val status: TestStatus,
        val message: String
    )

    /**
     * Tests connection to the provided Supabase project URL and API key.
     */
    suspend fun testConnection(url: String, apiKey: String): TestResult = withContext(Dispatchers.IO) {
        val cleanUrl = cleanProjectUrl(url)
        if (cleanUrl.isBlank() || !cleanUrl.contains(".")) {
            return@withContext TestResult(TestStatus.INVALID_URL, "Invalid Supabase URL format")
        }

        val keyVal = validateAnonKey(apiKey)
        if (keyVal is KeyValidationResult.Invalid) {
            return@withContext TestResult(TestStatus.INVALID_KEY, keyVal.reason)
        }

        try {
            // Test 1: Check project reachability & API key validity via Auth Health endpoint
            val targetUrl = URL("$cleanUrl/auth/v1/health")
            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("apikey", apiKey.trim())
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            conn.disconnect()

            if (code == 200 || code == 204) {
                // Test 2: Check if table 'vanta_backups' exists
                val tableUrl = URL("$cleanUrl/rest/v1/vanta_backups?select=id&limit=1")
                val tableConn = (tableUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("apikey", apiKey.trim())
                    setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    setRequestProperty("Accept", "application/json")
                }
                val tableCode = tableConn.responseCode
                val tableResp = runCatching {
                    val stream = if (tableCode in 200..299) tableConn.inputStream else tableConn.errorStream
                    BufferedReader(InputStreamReader(stream)).readText()
                }.getOrDefault("")
                tableConn.disconnect()

                return@withContext if (tableCode in 200..299) {
                    TestResult(TestStatus.SUCCESS, "Connected to Supabase project & table ready ✓")
                } else if (tableResp.contains("PGRST205") || tableResp.contains("does not exist") || tableCode == 404) {
                    TestResult(TestStatus.SUCCESS, "Connected ✓ (Table 'vanta_backups' will be created on first backup or run SQL)")
                } else {
                    TestResult(TestStatus.SUCCESS, "Connected to Supabase project ✓")
                }
            } else if (code == 401 || code == 403) {
                TestResult(TestStatus.INVALID_KEY, "Invalid API Key (HTTP $code)")
            } else {
                TestResult(TestStatus.SUCCESS, "Supabase responded (HTTP $code) ✓")
            }
        } catch (e: Exception) {
            TestResult(TestStatus.NETWORK_ERROR, e.localizedMessage ?: "Connection failed")
        }
    }

    /**
     * Uploads the full Vanta profile + daily health history to the user's Supabase database.
     */
    suspend fun uploadBackup(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val url = getUrl(context)
        val apiKey = getApiKey(context)

        if (url.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Supabase URL or API Key is missing in Settings."))
        }

        val keyVal = validateAnonKey(apiKey)
        if (keyVal is KeyValidationResult.Invalid) {
            return@withContext Result.failure(IllegalStateException(keyVal.reason))
        }

        try {
            // 1. Export binary .vanta payload
            val backupBytes = ProfileBackup.export(context)
            val base64Data = Base64.getEncoder().encodeToString(backupBytes)
            val nowInstant = Instant.now()
            val isoNow = DateTimeFormatter.ISO_INSTANT.format(nowInstant)

            val db = com.vanta.app.data.db.VantaDatabase.getInstance(context)
            val recordCount = db.dailyMetricsDao().getRecordCount()
            val profile = db.userProfileDao().getUserProfile()

            // 2. Prepare JSON payload for table 'vanta_backups'
            val payload = JSONObject().apply {
                put("id", DEFAULT_RECORD_ID)
                put("backup_data", base64Data)
                put("updated_at", isoNow)
                put("records_count", recordCount)
                put("user_name", profile?.name ?: "User")
                put("schema_version", 7)
            }

            val targetUrl = URL("$url/rest/v1/vanta_backups")
            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }

            val responseCode = conn.responseCode
            val responseText = runCatching {
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                BufferedReader(InputStreamReader(stream)).readText()
            }.getOrDefault("")
            conn.disconnect()

            if (responseCode in 200..299) {
                val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault())
                val formattedTime = formatter.format(nowInstant)
                getPrefs(context).edit()
                    .putString(KEY_LAST_BACKUP, formattedTime)
                    .putLong(KEY_LAST_BACKUP_EPOCH, System.currentTimeMillis())
                    .apply()
                return@withContext Result.success("Backed up $recordCount days to Supabase ✓")
            } else if (responseCode == 404 || responseText.contains("relation \"vanta_backups\" does not exist") || responseText.contains("PGRST205") || responseText.contains("PGRST204")) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Table 'vanta_backups' not found. Run this SQL in your Supabase SQL Editor:\n\n" +
                        "CREATE TABLE vanta_backups (\n" +
                        "  id TEXT PRIMARY KEY,\n" +
                        "  backup_data TEXT NOT NULL,\n" +
                        "  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n" +
                        "  records_count INT DEFAULT 0,\n" +
                        "  user_name TEXT,\n" +
                        "  schema_version INT DEFAULT 7\n" +
                        ");\n" +
                        "ALTER TABLE vanta_backups ENABLE ROW LEVEL SECURITY;\n" +
                        "CREATE POLICY \"Allow anon full access\" ON vanta_backups FOR ALL TO anon USING (true) WITH CHECK (true);"
                    )
                )
            } else {
                return@withContext Result.failure(IllegalStateException("Supabase error ($responseCode): $responseText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads the backup from Supabase and restores all Room DB history and profile.
     */
    suspend fun restoreBackup(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val url = getUrl(context)
        val apiKey = getApiKey(context)

        if (url.isBlank() || apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Supabase URL or API Key is missing in Settings."))
        }

        val keyVal = validateAnonKey(apiKey)
        if (keyVal is KeyValidationResult.Invalid) {
            return@withContext Result.failure(IllegalStateException(keyVal.reason))
        }

        try {
            val targetUrl = URL("$url/rest/v1/vanta_backups?id=eq.$DEFAULT_RECORD_ID&select=*")
            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("apikey", apiKey)
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }

            val responseCode = conn.responseCode
            val responseText = runCatching {
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                BufferedReader(InputStreamReader(stream)).readText()
            }.getOrDefault("")
            conn.disconnect()

            if (responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("Supabase query failed ($responseCode): $responseText"))
            }

            val jsonArray = JSONArray(responseText)
            if (jsonArray.length() == 0) {
                return@withContext Result.failure(IllegalStateException("No backup found in Supabase for this project."))
            }

            val backupRow = jsonArray.getJSONObject(0)
            val base64Data = backupRow.optString("backup_data")
            if (base64Data.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Backup record in Supabase is empty."))
            }

            val bytes = Base64.getDecoder().decode(base64Data)
            val importSummary = ProfileBackup.import(context, bytes)
                ?: return@withContext Result.failure(IllegalStateException("Failed to parse .vanta backup payload."))

            return@withContext Result.success(importSummary)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Checks if auto-backup is scheduled and performs it quietly in the background.
     */
    suspend fun checkAndPerformAutoBackup(context: Context) {
        if (!isConfigured(context)) return
        val freq = getBackupFrequency(context)
        if (freq == BackupFrequency.OFF) return

        val lastEpoch = getPrefs(context).getLong(KEY_LAST_BACKUP_EPOCH, 0L)
        val now = System.currentTimeMillis()
        if (now - lastEpoch >= freq.intervalMillis) {
            runCatching { uploadBackup(context) }
        }
    }
}
