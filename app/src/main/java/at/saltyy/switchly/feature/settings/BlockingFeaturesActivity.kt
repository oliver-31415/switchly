package at.saltyy.switchly.feature.settings

import android.os.Bundle

class BlockingFeaturesActivity : ToggleOptionsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra(EXTRA_VIEW_SECTION, SECTION_OTHER)
        super.onCreate(savedInstanceState)
    }
}
