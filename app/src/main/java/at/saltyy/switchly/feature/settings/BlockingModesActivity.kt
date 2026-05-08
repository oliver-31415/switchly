package at.saltyy.switchly.feature.settings

import android.os.Bundle

class BlockingModesActivity : ToggleOptionsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_VIEW_SECTION, SECTION_BLOCKING)
        super.onCreate(savedInstanceState)
    }
}
