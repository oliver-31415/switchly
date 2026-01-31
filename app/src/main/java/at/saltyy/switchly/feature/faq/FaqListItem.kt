package at.saltyy.switchly.feature.faq

sealed class FaqListItem {
    data class Header(val title: String) : FaqListItem()
    data class Item(
        val question: String,
        val answer: String,
        val iconRes: Int? = null
    ) : FaqListItem()
}
