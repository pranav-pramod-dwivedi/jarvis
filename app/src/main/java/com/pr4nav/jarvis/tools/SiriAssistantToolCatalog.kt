package com.pr4nav.jarvis.tools

import com.pr4nav.jarvis.tools.catalog.*

/**
 * Unified Siri & Google Assistant Tool Catalog for JARVIS.
 * Aggregates 350+ mobile assistant capabilities across Clock, Calendar, Reminders,
 * Contacts, Messaging, Calls, Media, Settings, Navigation, Weather, Notes, Camera,
 * Calculations, Web, Health, Accessibility, and System Utilities.
 */
object SiriAssistantToolCatalog {

    fun registerAll(register: (CanonicalToolDef) -> Unit) {
        ClockTimerTools.register(register)
        CalendarScheduleTools.register(register)
        ReminderTaskTools.register(register)
        ContactPhoneCommTools.register(register)
        MediaAudioTools.register(register)
        DeviceHardwareTools.register(register)
        ConnectivityNetworkTools.register(register)
        NavigationTravelTools.register(register)
        WeatherEnvironmentTools.register(register)
        NotesListsTools.register(register)
        CameraPhotosTools.register(register)
        CalculationConverterTools.register(register)
        WebKnowledgeTools.register(register)
        AppSystemShortcutTools.register(register)
        JarvisBrowserTools.register(register)
    }
}
