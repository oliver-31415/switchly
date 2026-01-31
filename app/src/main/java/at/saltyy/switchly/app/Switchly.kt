package at.saltyy.switchly.app

object Switchly {
    // Broadcast so UI/Service can refresh after a toggle
    const val ACTION_REFRESH = "at.saltyy.switchly.ACTION_REFRESH"

    // Optional: dedicated Intent for targeted toggling (e.g. via NFC tag)
    const val ACTION_TOGGLE = "at.saltyy.switchly.ACTION_TOGGLE"
}