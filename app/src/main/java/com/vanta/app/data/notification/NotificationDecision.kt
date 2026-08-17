package com.vanta.app.data.notification

/**
 * Result of a notification evaluation.
 *
 * [reason] drives the notification category filter and a stable notification id:
 *   recovery, workout, strain, achievement, weekly, goal.
 * [priority] is one of "low", "normal", "high".
 */
data class NotificationDecision(
    val notify: Boolean,
    val title: String,
    val message: String,
    val priority: String,
    val reason: String
)
