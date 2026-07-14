package org.jetbrains.kotlin.test.helper.reference

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.idea.stubindex.KotlinPropertyShortNameIndex
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtDeclarationWithReturnType

private const val LANGUAGE_FEATURE_FQ_NAME = "org.jetbrains.kotlin.config.LanguageFeature"

fun getLanguageFeatureClasses(project: Project): List<PsiClass> {
    val psiFacade = JavaPsiFacade.getInstance(project)

    return resolvePreferringProjectScope(project) {
        psiFacade.findClasses(LANGUAGE_FEATURE_FQ_NAME, it).toList()
    }
}

private val VALUE_DIRECTIVE_CLASS_ID =
    ClassId.topLevel(FqName("org.jetbrains.kotlin.test.directives.model.ValueDirective"))

fun getEnumClassesByDirective(key: String, project: Project): List<KtLightClass> {
    return resolvePreferringProjectScope(project) {
        val declarations =
            KotlinPropertyShortNameIndex.Helper[key, project, GlobalSearchScope.allScope(project)]

        declarations.mapNotNull {
            if (it !is KtDeclarationWithReturnType) return@mapNotNull null
            analyze(it) {
                if (it.returnType.isClassType(VALUE_DIRECTIVE_CLASS_ID)) {
                    ((it.returnType as KaClassType).typeArguments.firstOrNull()?.type?.expandedSymbol?.psi as? KtClass)?.toLightClass()
                } else {
                    null
                }
            }
        }
    }
}

private const val FIR_ERRORS_FQ_NAME = "org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors"

fun getFirErrorClasses(project: Project): List<PsiClass> {
    val psiFacade = JavaPsiFacade.getInstance(project)

    return resolvePreferringProjectScope(project) {
        psiFacade.findClasses(FIR_ERRORS_FQ_NAME, it).toList()
    }
}