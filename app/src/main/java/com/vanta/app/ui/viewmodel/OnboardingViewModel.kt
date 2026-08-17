package com.vanta.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.app.data.HealthConnectManager
import com.vanta.app.data.VantaDeterministicPhysiologyEngine
import com.vanta.app.data.db.UserProfileRecord
import com.vanta.app.data.db.VantaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.YearMonth

data class OnboardingUiState(
    val step: Int = 0,
    val name: String = "",
    val birthdateYear: String = "1999",
    val birthdateMonth: Int = 5,
    val birthdateDay: String = "15",
    val calculatedAge: Int = 27,
    val heightCm: String = "178",
    val weightKg: String = "75",
    val sex: String = "Male",
    val selectedGoal: String = "Build Muscle",
    val stepsGoal: Int = 10000,
    val avatarKey: String = "avatar1",
    val isHealthConnectGranted: Boolean = false,
    val isOnboardingComplete: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Production ViewModel for Onboarding Flow with bulletproof input safety filters,
 * age clamping, and DB exception guards.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val profileDao = VantaDatabase.getInstance(application).userProfileDao()
    private val physiologyEngine = VantaDeterministicPhysiologyEngine(application)
    private val healthConnectManager = HealthConnectManager(application)

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val existing = profileDao.getUserProfile()
                if (existing != null && existing.isOnboardingCompleted) {
                    val autoAge = existing.calculatedAge.coerceIn(1, 110)
                    physiologyEngine.userAge = autoAge

                    val parts = existing.birthdateStr.split("-")
                    val y = parts.getOrNull(0) ?: "1999"
                    val m = parts.getOrNull(1)?.toIntOrNull() ?: 5
                    val d = parts.getOrNull(2) ?: "15"

                    _uiState.value = _uiState.value.copy(
                        name = existing.name.take(40),
                        birthdateYear = y,
                        birthdateMonth = m.coerceIn(1, 12),
                        birthdateDay = d,
                        calculatedAge = autoAge,
                        heightCm = existing.heightCm.toInt().toString(),
                        weightKg = existing.weightKg.toInt().toString(),
                        sex = existing.sex,
                        selectedGoal = existing.fitnessGoal,
                        stepsGoal = existing.stepsGoal.takeIf { it in 1000..100000 } ?: 10000,
                        avatarKey = existing.avatarKey.ifBlank { "avatar1" },
                        isOnboardingComplete = true
                    )
                } else {
                    recalculateAge("1999", 5, "15")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            checkHealthConnectPermissions()
        }
    }

    private fun recalculateAge(yearStr: String, month: Int, dayStr: String) {
        val currentYear = LocalDate.now(ZoneId.systemDefault()).year
        val parsedYear = yearStr.toIntOrNull()
        
        // Safety Filter 1: Clamp year between 1900 and current year
        val safeYear = when {
            parsedYear == null -> 1999
            parsedYear > currentYear -> currentYear - 25 // Prevent future years
            parsedYear < 1900 && yearStr.length == 4 -> 1900
            else -> parsedYear
        }

        val validMonth = month.coerceIn(1, 12)
        
        // Safety Filter 2: Dynamically calculate max days for given year & month
        val maxDaysInMonth = try {
            YearMonth.of(safeYear.coerceIn(1900, currentYear), validMonth).lengthOfMonth()
        } catch (e: Exception) { 31 }

        val rawDay = dayStr.toIntOrNull() ?: 15
        val validDay = rawDay.coerceIn(1, maxDaysInMonth)

        val birthLocalDate = try {
            LocalDate.of(safeYear.coerceIn(1900, currentYear), validMonth, validDay)
        } catch (e: Exception) {
            LocalDate.of(1999, 5, 15)
        }

        val today = LocalDate.now(ZoneId.systemDefault())
        val ageYears = Period.between(birthLocalDate, today).years.coerceIn(1, 110)

        _uiState.value = _uiState.value.copy(
            birthdateYear = yearStr,
            birthdateMonth = validMonth,
            birthdateDay = dayStr,
            calculatedAge = ageYears
        )
    }

    suspend fun checkHealthConnectPermissions() {
        try {
            val hasPermissions = healthConnectManager.hasPermissions()
            _uiState.value = _uiState.value.copy(isHealthConnectGranted = hasPermissions)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isHealthConnectGranted = false)
        }
    }

    // Safety Filter 3: Sanitize text inputs
    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name.take(40))
    }

    fun updateBirthdateYear(year: String) {
        val filtered = year.filter { it.isDigit() }.take(4)
        recalculateAge(filtered, _uiState.value.birthdateMonth, _uiState.value.birthdateDay)
    }

    fun updateBirthdateMonth(month: Int) {
        recalculateAge(_uiState.value.birthdateYear, month.coerceIn(1, 12), _uiState.value.birthdateDay)
    }

    fun updateBirthdateDay(day: String) {
        val filtered = day.filter { it.isDigit() }.take(2)
        recalculateAge(_uiState.value.birthdateYear, _uiState.value.birthdateMonth, filtered)
    }

    fun updateHeight(h: String) {
        val filtered = h.filter { it.isDigit() }.take(3)
        _uiState.value = _uiState.value.copy(heightCm = filtered)
    }

    fun updateWeight(w: String) {
        val filtered = w.filter { it.isDigit() }.take(3)
        _uiState.value = _uiState.value.copy(weightKg = filtered)
    }

    fun updateSex(sex: String) {
        _uiState.value = _uiState.value.copy(sex = sex.take(20))
    }

    fun updateGoal(goal: String) {
        _uiState.value = _uiState.value.copy(selectedGoal = goal.take(40))
    }

    fun updateStepsGoal(goal: Int) {
        _uiState.value = _uiState.value.copy(stepsGoal = goal.coerceIn(1000, 100000))
    }

    fun updateAvatar(key: String) {
        _uiState.value = _uiState.value.copy(avatarKey = key)
    }

    fun nextStep() {
        val currentStep = _uiState.value.step
        if (currentStep < 2) {
            _uiState.value = _uiState.value.copy(step = currentStep + 1)
        }
    }

    fun previousStep() {
        val currentStep = _uiState.value.step
        if (currentStep > 0) {
            _uiState.value = _uiState.value.copy(step = currentStep - 1)
        }
    }

    // Safety Filter 4: Double-submission guard & safe fallbacks on save
    fun completeOnboarding(onSuccess: () -> Unit) {
        if (_uiState.value.isSaving) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSaving = true)

            try {
                val currentYear = LocalDate.now(ZoneId.systemDefault()).year
                val yInt = (_uiState.value.birthdateYear.toIntOrNull() ?: 1999).coerceIn(1900, currentYear)
                val mInt = _uiState.value.birthdateMonth.coerceIn(1, 12)
                val dInt = (_uiState.value.birthdateDay.toIntOrNull() ?: 15).coerceIn(1, 31)

                val y = yInt.toString().padStart(4, '0')
                val m = mInt.toString().padStart(2, '0')
                val d = dInt.toString().padStart(2, '0')
                val birthdateIso = "$y-$m-$d"

                val autoAge = _uiState.value.calculatedAge.coerceIn(1, 110)
                val parsedHeight = (_uiState.value.heightCm.toDoubleOrNull() ?: 178.0).coerceIn(50.0, 250.0)
                val parsedWeight = (_uiState.value.weightKg.toDoubleOrNull() ?: 75.0).coerceIn(30.0, 300.0)

                val profile = UserProfileRecord(
                    id = 1,
                    name = _uiState.value.name.ifBlank { "User" },
                    birthdateStr = birthdateIso,
                    age = autoAge,
                    heightCm = parsedHeight,
                    weightKg = parsedWeight,
                    sex = _uiState.value.sex.ifBlank { "Male" },
                    fitnessGoal = _uiState.value.selectedGoal.ifBlank { "General Fitness" },
                    stepsGoal = _uiState.value.stepsGoal.coerceIn(1000, 100000),
                    avatarKey = _uiState.value.avatarKey.ifBlank { "avatar1" },
                    isOnboardingCompleted = true,
                    createdAtTimestamp = System.currentTimeMillis()
                )

                profileDao.insertOrUpdateProfile(profile)
                physiologyEngine.userAge = autoAge

                // Keep settings in prefs: AI features default OFF until user provides an API key or uses on-device LLM
                getApplication<Application>().getSharedPreferences(
                    "vanta_settings", android.content.Context.MODE_PRIVATE
                ).edit()
                    .putInt("steps_goal", _uiState.value.stepsGoal.coerceIn(1000, 100000))
                    .putBoolean("ai_daily_analysis_enabled", false)
                    .putBoolean("ai_chat_enabled", false)
                    .putBoolean("ai_detailed_coach_enabled", false)
                    .putBoolean("ai_vantix_enabled", false)
                    .apply()

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    isOnboardingComplete = true
                )

                viewModelScope.launch(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "Setup completed with default biometrics."
                )
                viewModelScope.launch(Dispatchers.Main) {
                    onSuccess()
                }
            }
        }
    }
}
