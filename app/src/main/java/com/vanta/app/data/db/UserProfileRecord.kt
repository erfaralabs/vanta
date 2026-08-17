package com.vanta.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

/**
 * Room DB Entity for storing the user's profile, biometrics, birthdate, fitness goals,
 * and onboarding status on-device.
 * Automatically computes exact age based on birthdate and current date.
 */
@Entity(tableName = "user_profile")
data class UserProfileRecord(
    @PrimaryKey
    val id: Int = 1,
    val name: String,
    val birthdateStr: String = "1999-01-01", // ISO Format YYYY-MM-DD
    val age: Int = 27,
    val heightCm: Double = 178.0,
    val weightKg: Double = 75.0,
    val sex: String = "Not Specified",
    val fitnessGoal: String = "General Fitness",
    val stepsGoal: Int = 10000,
    val avatarKey: String = "avatar1", // "avatar1" | "avatar2" | "custom"
    val isOnboardingCompleted: Boolean = true,
    val createdAtTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Automatically computes current age in years based on birthdate and today's date.
     * Updates automatically on every birthday.
     */
    val calculatedAge: Int
        get() = try {
            val birthLocalDate = LocalDate.parse(birthdateStr)
            val today = LocalDate.now(ZoneId.systemDefault())
            Period.between(birthLocalDate, today).years.coerceAtLeast(1)
        } catch (e: Exception) {
            age
        }
}
