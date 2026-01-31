package at.saltyy.switchly.feature.onboarding.adapters

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.onboarding.OnboardingPage
import com.google.android.material.button.MaterialButton

class OnboardingPagerAdapter(
    private val activity: Activity,
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<OnboardingPagerAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(activity, pages[position])
    }

    override fun getItemCount(): Int = pages.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon = itemView.findViewById<ImageView>(R.id.icon)
        private val title = itemView.findViewById<TextView>(R.id.title)
        private val desc = itemView.findViewById<TextView>(R.id.desc)
        private val badge = itemView.findViewById<TextView>(R.id.badge)
        private val btn = itemView.findViewById<MaterialButton>(R.id.btn_action)

        fun bind(activity: Activity, page: OnboardingPage) {
            title.text = page.title
            desc.text = page.desc

            if (page.iconRes != null) {
                icon.isVisible = true
                icon.setImageResource(page.iconRes)
            } else {
                icon.isVisible = false
            }

            badge.text = when (page.level) {
                OnboardingPage.Level.REQUIRED -> activity.getString(R.string.onb_badge_required)
                OnboardingPage.Level.RECOMMENDED -> activity.getString(R.string.onb_badge_recommended)
                OnboardingPage.Level.OPTIONAL -> activity.getString(R.string.onb_badge_optional)
                OnboardingPage.Level.INFO -> ""
            }
            badge.isVisible = page.level != OnboardingPage.Level.INFO

            val hasAction = page.action != null && !page.actionLabel.isNullOrBlank()
            btn.isVisible = hasAction
            if (hasAction) {
                btn.text = page.actionLabel
                btn.setOnClickListener { page.action?.invoke(activity) }
            } else {
                btn.setOnClickListener(null)
            }
        }
    }
}
