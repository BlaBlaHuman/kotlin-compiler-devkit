package org.jetbrains.kotlin.test.helper.lang

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.KotlinLanguage
import javax.swing.Icon

/**
 * Provides the native **File Structure** popup for test data files:
 * a tree of `// MODULE:` nodes with their `// FILE:` children.
 * Each `// FILE:` node expands into the tree structure of its content.
 * Selecting a node navigates to that directive.
 */
class MultifileTestDataStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile !is MultifileTestDataTextFileImpl) return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                MultifileTestDataStructureViewModel(psiFile, editor)
        }
    }
}

private class MultifileTestDataStructureViewModel(psiFile: MultifileTestDataTextFileImpl, editor: Editor?) :
    StructureViewModelBase(psiFile, editor, FileElement(psiFile)),
    StructureViewModel.ElementInfoProvider,
    StructureViewModel.ExpandInfoProvider {

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean =
        element is ModuleElement || element is ModuleFileElement

    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean = false

    override fun isAutoExpand(element: StructureViewTreeElement): Boolean = true
    override fun isSmartExpand(): Boolean = false
}

/**
 * Physical test data files representation
 */
private class FileElement(private val file: MultifileTestDataTextFileImpl) : BaseElement(file) {
    override fun getPresentation(): ItemPresentation =
        MultifilePresentation(file.name, file.getIcon(0))

    override fun getChildren(): Array<TreeElement> {
        val looseFiles = mutableListOf<TreeElement>()
        val modules = mutableListOf<ModuleElement>()
        var currentModule: ModuleElement? = null

        for (entry in file.entries) {
            entry.moduleHeader?.let { header ->
                currentModule = ModuleElement(header).also(modules::add)
            }
            /**
             * Every single entry is unconditionally added as a separate file node.
             * That's because test data files don't always have proper `// FILE` / `// MODULE` directives.
             */
            val fileNode = ModuleFileElement(currentModule?.header, entry)
            currentModule?.addFile(fileNode) ?: looseFiles.add(fileNode)
        }
        return (looseFiles + modules).toTypedArray()
    }
}

/**
 * Test data module representation
 */
private class ModuleElement(val header: MultifileTestDataModuleHeader) : BaseElement(header) {
    private val files = mutableListOf<TreeElement>()

    fun addFile(file: ModuleFileElement) {
        files.add(file)
    }

    override fun getPresentation(): ItemPresentation =
        MultifilePresentation(header.moduleName.ifBlank { "<module>" }, AllIcons.Nodes.Module)

    override fun getChildren(): Array<TreeElement> = files.toTypedArray()
}

/**
 * Test data file representation
 */
private class ModuleFileElement(
    private val containingModuleHeader: MultifileTestDataModuleHeader?,
    private val entry: MultifileTestDataEntry,
) : BaseElement(entry) {
    private val fileChildren: Array<TreeElement> by lazy { buildDelegatedChildren() }

    /**
     * Used to show the test data file name in the structure view tree.
     * Handles various [entry] cases:
     * - If [entry] has a proper `// FILE` header, the name is taken from there
     * - If [entry] is placed right under some `// MODULE` directive, the module name is taken with `.kt` extension
     * - If [entry] doesn't have any `// MODULE` / `// FILE` header, the test file name is taken
     */
    private fun getDisplayName(): String {
        return entry.fileHeader?.fileName?.takeIf { it.isNotBlank() }
            ?: containingModuleHeader?.moduleName?.plus(".kt")
            ?: entry.containingFile.name
    }

    override fun getPresentation(): ItemPresentation {
        val fileName = getDisplayName()
        val icon = FileTypeRegistry.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.FileTypes.Any_type
        return MultifilePresentation(fileName, icon)
    }

    override fun getChildren(): Array<TreeElement> = fileChildren

    private fun buildDelegatedChildren(): Array<TreeElement> {
        val content = entry.content ?: return TreeElement.EMPTY_ARRAY
        val textBlock = PsiTreeUtil.findChildOfType(content, MultifileTestDataTextBlock::class.java)
            ?: return TreeElement.EMPTY_ARRAY
        val text = textBlock.text.ifEmpty { return TreeElement.EMPTY_ARRAY }

        val language =
            entry.fileHeader?.injectedLanguage.takeUnless { it == PlainTextLanguage.INSTANCE } ?: KotlinLanguage.INSTANCE
        val standalone = PsiFileFactory.getInstance(entry.project)
            .createFileFromText(getDisplayName(), language, text) ?: return TreeElement.EMPTY_ARRAY
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(standalone)
                as? TreeBasedStructureViewBuilder ?: return TreeElement.EMPTY_ARRAY

        val model = builder.createStructureViewModel(null)
        val hostFile = entry.containingFile ?: return TreeElement.EMPTY_ARRAY
        val baseOffset = textBlock.textRange.startOffset
        return model.root.children.remapTo(baseOffset, hostFile).also {
            model.dispose()
        }
    }
}

private abstract class BaseElement(private val element: NavigatablePsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        element.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = element.canNavigate()

    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()
}

/**
 * Wraps injected structure elements (whose PSI lives in a standalone in-memory file) so navigation targets
 * the corresponding offset in the physical test data [hostFile].
 */
private class InjectedLanguageElement(
    private val delegate: StructureViewTreeElement,
    private val baseOffset: Int,
    private val hostFile: PsiFile,
) : StructureViewTreeElement by delegate {
    private val remappedChildren = delegate.children.remapTo(baseOffset, hostFile)

    override fun getChildren(): Array<TreeElement> = remappedChildren

    @Suppress("UnstableApiUsage")
    override fun navigationRequest(): NavigationRequest? {
        return delegate.navigationRequest()
    }

    override fun navigate(requestFocus: Boolean) {
        val psi = delegate.value as? PsiElement ?: return
        val virtualFile = hostFile.virtualFile ?: return
        OpenFileDescriptor(hostFile.project, virtualFile, baseOffset + psi.textOffset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = delegate.value is PsiElement && hostFile.virtualFile != null
    override fun canNavigateToSource(): Boolean = canNavigate()
}

private fun Array<TreeElement>.remapTo(baseOffset: Int, hostFile: PsiFile): Array<TreeElement> =
    mapNotNull {
        (it as? StructureViewTreeElement)?.let { element ->
            InjectedLanguageElement(element, baseOffset, hostFile)
        }
    }.toTypedArray()

private class MultifilePresentation(private val text: String, private val icon: Icon?) : ItemPresentation {
    override fun getPresentableText(): String = text
    override fun getLocationString(): String? = null
    override fun getIcon(unused: Boolean): Icon? = icon
}
