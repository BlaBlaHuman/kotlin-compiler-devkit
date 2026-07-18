package org.jetbrains.kotlin.test.helper.lang

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/**
 * Provides the native **File Structure** popup for test data files:
 * a tree of `// MODULE:` nodes with their `// FILE:` children.
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
    StructureViewModel.ElementInfoProvider {
    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean = element is ModuleElement

    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean = false
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
            entry.fileHeader?.let { header ->
                val fileNode = ModuleFileElement(header)
                currentModule?.addFile(fileNode) ?: looseFiles.add(fileNode)
            }
        }
        return (looseFiles + modules).toTypedArray()
    }
}

/**
 * Test data module representation
 */
private class ModuleElement(private val header: MultifileTestDataModuleHeader) : BaseElement(header) {
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
private class ModuleFileElement(private val header: MultifileTestDataFileHeader) : BaseElement(header) {
    override fun getPresentation(): ItemPresentation {
        val fileName = header.fileName.ifBlank { "<file>" }
        val icon = FileTypeRegistry.getInstance().getFileTypeByFileName(fileName).icon ?: AllIcons.FileTypes.Any_type
        return MultifilePresentation(fileName, icon)
    }

    override fun getChildren(): Array<TreeElement> = TreeElement.EMPTY_ARRAY
}

private abstract class BaseElement(private val element: NavigatablePsiElement) : StructureViewTreeElement {
    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        element.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = element.canNavigate()

    override fun canNavigateToSource(): Boolean = element.canNavigateToSource()
}

private class MultifilePresentation(private val text: String, private val icon: Icon?) : ItemPresentation {
    override fun getPresentableText(): String = text
    override fun getLocationString(): String? = null
    override fun getIcon(unused: Boolean): Icon? = icon
}
