package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiField
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.ResolveResult
import org.jetbrains.kotlin.asJava.unwrapped

typealias ClassProvider = (Project) -> Collection<PsiClass>

/**
 * A PSI reference for resolution to a single field member inside some class
 *
 * @param element The host of the reference
 * @param range Reference text range inside [element]
 * @property memberShortName Short name of the target member
 * @property containingClassProvider Provider for the enclosing class of the target member
 */
class SingleMemberReference(
    element: PsiElement,
    range: TextRange,
    private val memberShortName: String,
    private val containingClassProvider: ClassProvider
) : PsiReferenceBase<PsiElement>(element, range), PsiPolyVariantReference {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = myElement.project
        val classes = containingClassProvider(project)

        return classes.mapNotNull { clazz ->
            clazz.fields
                .filterIsInstance<PsiField>()
                .firstOrNull { it.name == memberShortName }
                ?.let { PsiElementResolveResult(it.unwrapped ?: it) }
        }.toTypedArray()
    }

    override fun resolve(): PsiElement? {
        return multiResolve(false).singleOrNull()?.element
    }
}