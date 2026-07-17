package org.jetbrains.kotlin.test.helper.codeinsight

import com.intellij.lang.Language
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.impl.source.tree.PsiCommentImpl
import org.jetbrains.annotations.Unmodifiable
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.test.helper.isTestDataFile
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataFileContent
import org.jetbrains.kotlin.test.helper.lang.MultifileTestDataTextBlock

class MultifileTestDataMultiHostInjector: MultiHostInjector {
    private val supportedElementTypes: List<Class<out PsiElement>> =
        listOf(MultifileTestDataFileContent::class.java, PsiComment::class.java, MultifileTestDataTextBlock::class.java)

    /**
     * Diagnostic markers (`<!DIAGNOSTIC!>` … `<!>`) are not valid code. They are excluded from the
     * injected fragment so that the hints / resolution is calculated against the host
     * test file instead of the injected editor.
     *
     * Otherwise, some features, e.g., underscore hints for `DIAGNOSTICS` references are shown incorrectly.
     * That's because [org.jetbrains.kotlin.test.helper.reference.ReportedErrorReferenceContributor] calculates
     * reference ranges based on the whole `.kt` test file, which contains all the inner files as well as preambles.
     * If `<!DIAGNOSTIC!>` is injected as well, the text ranges of the reference for the highlighting
     * are shifted to the right by the start offset of the injected fragment.
     *
     * This regex is used to keep `<!DIAGNOSTIC!>` … `<!>` from being injected.
     */
    private val diagnosticMarkerRegex = Regex("<![A-Z].*?!>|<!>")

    /**
     * Regex for finding `<caret_label>` markers.
     *
     * These markers are not real Kotlin code and thus should not be injected.
     */
    val caretMarkerRegex = Regex("<caret(?:_\\w+)?>")

    /**
     * Regex for finding `<expr_label>` / `</expr_label>` markers.
     *
     * These markers are not real Kotlin code and thus should not be injected.
     */
    val exprMarkerRegex = Regex("</?expr(?:_\\w+)?>")

    val allMarkerRegex = listOf(diagnosticMarkerRegex, caretMarkerRegex, exprMarkerRegex)

    override fun getLanguagesToInject(
        registrar: MultiHostRegistrar,
        context: PsiElement
    ) {
        if (context is PsiCommentImpl && context.containingFile.virtualFile.isTestDataFile(context.project)) {
            injectCommentsAsKotlin(registrar, context)
            return
        }

        val textBlock = (context as? MultifileTestDataTextBlock)?.takeIf { it.textLength != 0 } ?: return
        val language = when {
            textBlock.fileContent != null ->
                (textBlock.fileContent?.entry?.fileHeader?.injectedLanguage ?: KotlinLanguage.INSTANCE)
                    .takeUnless { it == PlainTextLanguage.INSTANCE }

            textBlock.preamble != null -> KotlinLanguage.INSTANCE
            else -> null
        } ?: return

        injectExcludingTestMarkers(registrar, textBlock, language)
    }

    /**
     * Adds an injection place for every code segment between diagnostic markers, leaving the
     * markers themselves as non-injected host text. See [diagnosticMarkerRegex].
     */
    private fun injectExcludingTestMarkers(
        registrar: MultiHostRegistrar,
        textBlock: MultifileTestDataTextBlock,
        language: Language
    ) {
        val text = textBlock.text
        val rangesToInject = mutableListOf<TextRange>()
        var segmentStart = 0

        val allFoundMarkers = allMarkerRegex.flatMap { it.findAll(text) }.sortedBy { it.range.first }
        for (marker in allFoundMarkers) {
            if (marker.range.first > segmentStart) {
                rangesToInject.add(TextRange(segmentStart, marker.range.first))
            }
            segmentStart = marker.range.last + 1
        }
        if (segmentStart < text.length) {
            rangesToInject.add(TextRange(segmentStart, text.length))
        }

        // If the whole file is just diagnostics, nothing should be injected
        if (rangesToInject.isNotEmpty()) {
            registrar.startInjecting(language)
            for (place in rangesToInject) {
                registrar.addPlace(null, null, textBlock, place)
            }
            registrar.doneInjecting()
        }
    }

    private fun injectCommentsAsKotlin(
        registrar: MultiHostRegistrar,
        context: PsiLanguageInjectionHost
    ) {
        val content = context.text.takeUnless { it.isEmpty() } ?: return
        registrar.startInjecting(KotlinLanguage.INSTANCE)
        val textRange = TextRange(0, content.length)
        registrar.addPlace(null, null, context, textRange)
        registrar.doneInjecting()
    }

    override fun elementsToInjectIn(): @Unmodifiable List<Class<out PsiElement>> =
        supportedElementTypes
}
