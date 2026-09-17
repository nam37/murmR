package dev.murmr.app.stt

/**
 * Appends a recognised phrase to a running transcript.
 *
 * The platform formatter capitalises the first word of every segment as if it began a
 * sentence. Within one hold most segment boundaries fall mid-sentence (they are pauses), so
 * that capital is wrong far more often than it is a name. When the text so far does not end a
 * sentence, the new phrase's first letter is lowered, except for "I" and its contractions and
 * for acronyms. The cost is a name at a mid-sentence pause losing its capital; the benefit is
 * every pause no longer starting a false sentence.
 */
fun StringBuilder.appendPhrase(phrase: String) {
    var text = phrase.trim()
    if (text.isEmpty()) return
    if (isNotEmpty()) {
        if (!endsSentence(this) && shouldLowerFirst(text)) {
            text = text.replaceFirstChar { it.lowercaseChar() }
        }
        append(' ')
    }
    append(text)
}

private fun endsSentence(text: CharSequence): Boolean {
    var i = text.length - 1
    while (i >= 0 && (text[i].isWhitespace() || text[i] in CLOSERS)) i--
    return i < 0 || text[i] in SENTENCE_END
}

private fun shouldLowerFirst(text: String): Boolean {
    if (!text[0].isUpperCase()) return false
    if (text.length == 1) return false                       // "I"
    if (text[1] == '\'' || text[1] == '’') return false  // I'm, I'll, I've, I'd
    return text[1].isLowerCase()                              // "It" yes; "NASA" or "I," no
}

private const val SENTENCE_END = ".!?"
private const val CLOSERS = "\"'”’)]"
