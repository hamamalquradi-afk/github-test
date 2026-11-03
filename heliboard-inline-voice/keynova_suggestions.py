#!/usr/bin/env python3
"""Add a scrollable 5/7/10 candidate row to pinned HeliBoard without altering keys."""
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/java/helium314/keyboard"
view = base / "latin/suggestions/SuggestionStripView.kt"
screen = base / "settings/screens/TextCorrectionScreen.kt"

def once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError("KEYNOVA suggestion source drift: " + old[:100])
    return text.replace(old, new, 1)

s = view.read_text()
s = once(s, "import android.widget.ImageButton\n",
         "import android.widget.ImageButton\nimport android.widget.HorizontalScrollView\n")
s = once(s, "    private var startIndexOfMoreSuggestions = 0\n",
         "    private var startIndexOfMoreSuggestions = 0\n    private var keynovaRow: LinearLayout? = null\n")
s = once(s, """        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        isExternalSuggestionVisible = false""",
"""        val wanted = context.prefs().getString("keynova_suggestion_count", "10")?.toIntOrNull()
            ?.takeIf { it == 5 || it == 7 || it == 10 } ?: 10
        startIndexOfMoreSuggestions = if (suggestedWords.isPunctuationSuggestions)
            layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(context, suggestedWords, suggestionsStrip, this)
        else showKeynovaSuggestions(suggestedWords, wanted)
        isExternalSuggestionVisible = false""")
needle = "    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {"
extra = """    /** Only the suggestion strip gains scrolling; keyboard geometry remains unchanged.
     * All labels are from HeliBoard's ranked candidates and retain original tags.
     * Never invent dictionary suggestions to fill the user's selected capacity. */
    private fun showKeynovaSuggestions(words: SuggestedWords, capacity: Int): Int {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        keynovaRow = row
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        suggestionsStrip.addView(scroll, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val omitTyped = words.mInputStyle == SuggestedWords.INPUT_STYLE_TYPING ||
            words.mInputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH
        var displayed = 0
        var visited = 0
        for (i in 0 until words.size()) {
            visited = i + 1
            if (i == SuggestedWords.INDEX_OF_TYPED_WORD && omitTyped) continue
            val word = words.getLabel(i)
            if (word.isNullOrEmpty()) continue
            val view = wordViews[displayed]
            view.tag = i
            view.text = word
            view.contentDescription = word
            view.setTextScaleX(1f)
            view.setCompoundDrawables(null, null, null, null)
            view.isEnabled = true
            view.setTextColor(Settings.getValues().mColors.get(
                if (words.mWillAutoCorrect && i == SuggestedWords.INDEX_OF_AUTO_CORRECTION)
                    ColorType.SUGGESTION_AUTO_CORRECT else ColorType.SUGGESTED_WORD))
            val minWidth = 88.dpToPx(resources)
            val maxWidth = 220.dpToPx(resources)
            val desired = (view.paint.measureText(word) + 36.dpToPx(resources)).toInt()
            row.addView(view, LinearLayout.LayoutParams(desired.coerceIn(minWidth, maxWidth),
                ViewGroup.LayoutParams.MATCH_PARENT))
            displayed++
            if (displayed == capacity) break
        }
        return visited
    }

"""
s = once(s, needle, extra+needle)
s = once(s, """    private fun clear() {
        suggestionsStrip.removeAllViews()""", """    private fun clear() {
        keynovaRow?.removeAllViews()
        keynovaRow = null
        suggestionsStrip.removeAllViews()""")
view.write_text(s)

s = screen.read_text()
s = once(s,"import helium314.keyboard.settings.preferences.Preference\n",
           "import helium314.keyboard.settings.preferences.Preference\nimport helium314.keyboard.settings.preferences.ListPreference\n")
s = once(s,"        if (suggestionsEnabled) Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER else null,",
           """        if (suggestionsEnabled) Settings.PREF_CENTER_SUGGESTION_TEXT_TO_ENTER else null,
        if (suggestionsEnabled) "keynova_suggestion_count" else null,""")
s = once(s,"    Setting(context, Settings.PREF_ALWAYS_SHOW_SUGGESTIONS,",
           """    Setting(context, "keynova_suggestion_count", R.string.keynova_suggestion_count,
        R.string.keynova_suggestion_count_summary) {
        ListPreference(it, listOf("5" to "5", "7" to "7", "10" to "10"), "10")
    },
    Setting(context, Settings.PREF_ALWAYS_SHOW_SUGGESTIONS,""")
screen.write_text(s)

strings = [
    ("values", "Suggestions displayed", "Show 5, 7 or 10 scrollable word suggestions"),
    ("values-ar", "عدد الكلمات المقترحة", "عرض 5 أو 7 أو 10 اقتراحات مع التمرير الأفقي"),
]
for qualifier, title, description in strings:
    dest = root / "app/src/main/res" / qualifier / "keynova_suggestions.xml"
    import xml.sax.saxutils as xml
    dest.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'  <string name="keynova_suggestion_count">{xml.escape(title)}</string>\n'
        f'  <string name="keynova_suggestion_count_summary">{xml.escape(description)}</string>\n'
        '</resources>\n')
print("KEYNOVA: scrollable suggestion count 5/7/10, default 10, settings added")
