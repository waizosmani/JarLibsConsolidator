package org.le1a.jarlibsconsolidator

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.JarFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 一键添加jar依赖的Action类
 * 兼容多个IDEA版本 (243.x - 251.x+)
 */
class AddJarDependenciesAction : AnAction() {

    // 版本检测：2025.1 对应 build 251
    private val isNewThreadingModel: Boolean by lazy {
        val buildNumber = ApplicationInfo.getInstance().build.baselineVersion
        buildNumber >= 251 // 2025.1及以上版本使用新的线程模型
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // 获取项目根目录
        val basePath = project.basePath ?: run {
            showError(project, "无法获取项目根目录")
            return
        }

        val allInOneDir = File(basePath, "all-in-one")

        // 检查all-in-one文件夹是否已存在
        if (allInOneDir.exists()) {
            val result = Messages.showYesNoDialog(
                project,
                "all-in-one文件夹已存在，是否删除并重新创建？\n" +
                        "点击'是'将删除现有文件夹及其内容\n" +
                        "点击'否'将取消操作",
                "文件夹已存在",
                "删除并重新创建",
                "取消",
                Messages.getQuestionIcon()
            )

            if (result != Messages.YES) {
                return // 用户选择取消
            }

            // 删除现有文件夹
            try {
                allInOneDir.deleteRecursively()
            } catch (e: Exception) {
                showError(project, "无法删除现有文件夹: ${e.message}")
                return
            }
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "正在收集jar依赖...", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "正在扫描jar文件..."
                    indicator.fraction = 0.1

                    // 扫描jar文件
                    val jarFiles = findJarFiles(File(basePath), indicator)

                    if (jarFiles.isEmpty()) {
                        showInfo(project, "未找到任何jar文件")
                        return
                    }

                    indicator.text = "正在创建all-in-one目录..."
                    indicator.fraction = 0.3

                    // 创建目标目录
                    if (!allInOneDir.mkdirs()) {
                        throw RuntimeException("无法创建all-in-one目录")
                    }

                    indicator.text = "正在复制jar文件..."
                    indicator.fraction = 0.5

                    // 复制文件
                    copyJarFiles(jarFiles, allInOneDir, indicator)

                    indicator.text = "正在添加到项目库..."
                    indicator.fraction = 0.8

                    // 根据版本选择不同的添加方式
                    if (isNewThreadingModel) {
                        addDirectoryToLibrary_New(project, allInOneDir)
                    } else {
                        addDirectoryToLibrary_Old(project, allInOneDir)
                    }

                    indicator.fraction = 1.0
                    showSuccess(project, jarFiles.size)

                } catch (e: Exception) {
                    showError(project, "操作失败：${e.message}")
                }
            }
        })
    }

    /**
     * 递归查找所有jar文件
     */
    private fun findJarFiles(directory: File, indicator: ProgressIndicator): List<File> {
        val jarFiles = mutableListOf<File>()

        fun searchDirectory(dir: File) {
            if (indicator.isCanceled) return

            try {
                dir.listFiles()?.forEach { file ->
                    if (indicator.isCanceled) return

                    when {
                        file.isDirectory -> {
                            // 跳过常见的不需要搜索的目录，提高性能
                            if (!shouldSkipDirectory(file.name)) {
                                searchDirectory(file)
                            }
                        }
                        file.isFile && file.name.endsWith(".jar", ignoreCase = true) -> {
                            jarFiles.add(file)
                            indicator.text2 = "发现: ${file.name}"
                        }
                    }
                }
            } catch (e: Exception) {
                // 忽略无法访问的目录
            }
        }

        searchDirectory(directory)
        return jarFiles
    }

    /**
     * 判断是否应该跳过某些目录以提高性能
     */
    private fun shouldSkipDirectory(dirName: String): Boolean {
        return dirName.startsWith(".") ||
                dirName == "node_modules" ||
                dirName == "target" ||
                dirName == "build" ||
                dirName == ".gradle" ||
                dirName == ".mvn"
    }

    /**
     * 复制jar文件，添加重名处理
     */
    private fun copyJarFiles(jarFiles: List<File>, targetDir: File, indicator: ProgressIndicator) {
        val nameCount = mutableMapOf<String, Int>()

        jarFiles.forEachIndexed { index, jarFile ->
            if (indicator.isCanceled) return

            try {
                // 处理重名文件
                var targetName = jarFile.name
                val baseName = jarFile.nameWithoutExtension
                val extension = jarFile.extension

                if (nameCount.containsKey(targetName)) {
                    val count = nameCount[targetName]!! + 1
                    nameCount[targetName] = count
                    targetName = "${baseName}_$count.$extension"
                } else {
                    nameCount[targetName] = 1
                }

                val targetFile = File(targetDir, targetName)
                Files.copy(jarFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

                val progress = 0.5 + (index + 1).toDouble() / jarFiles.size * 0.3
                indicator.fraction = progress
                indicator.text2 = "复制: ${jarFile.name} (${index + 1}/${jarFiles.size})"

            } catch (e: Exception) {
                throw RuntimeException("复制文件失败: ${jarFile.name} -> ${e.message}")
            }
        }
    }

    /**
     * 2025.1+ 版本 (251+) 的库添加方法
     * 使用新的线程模型
     */
    private fun addDirectoryToLibrary_New(project: Project, allInOneDir: File) {
        ApplicationManager.getApplication().invokeLater {
            try {
                WriteAction.run<RuntimeException> {
                    performLibraryOperations(project, allInOneDir)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "添加到项目库失败：${e.message}", "错误")
                }
            }
        }
    }

    /**
     * 2024.3及以下版本 (243及以下) 的库添加方法
     * 使用旧的线程模型
     */
    private fun addDirectoryToLibrary_Old(project: Project, allInOneDir: File) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    performLibraryOperations(project, allInOneDir)
                } catch (e: Exception) {
                    throw RuntimeException("添加到项目库失败：${e.message}")
                }
            }
        }
    }

    /**
     * 核心库操作逻辑，两个版本共用
     */
    private fun performLibraryOperations(project: Project, allInOneDir: File) {
        // 刷新文件系统
        LocalFileSystem.getInstance().refresh(false)
        val allInOneVirtualDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(allInOneDir)
            ?: throw RuntimeException("无法找到all-in-one目录")
        allInOneVirtualDir.refresh(false, true)

        // 获取项目级别库表
        val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)

        // 安全地删除同名库
        val existingLibrary = projectLibraryTable.getLibraryByName("all-in-one")
        existingLibrary?.let { lib ->
            // 先从所有模块中移除引用
            removeLibraryFromAllModules(project, "all-in-one")
            // 然后删除库
            projectLibraryTable.removeLibrary(lib)
        }

        // 创建新库
        val library = projectLibraryTable.createLibrary("all-in-one")
        val libraryModel = library.modifiableModel

        try {
            // 为每个jar文件添加jar root
            val jarChildren = allInOneVirtualDir.children
                ?.filter { !it.isDirectory && it.extension?.equals("jar", true) == true }
                ?: emptyList()

            if (jarChildren.isEmpty()) {
                throw RuntimeException("all-in-one目录中没有找到jar文件")
            }

            jarChildren.forEach { vf ->
                try {
                    val jarRoot = JarFileSystem.getInstance().refreshAndFindFileByPath("${vf.path}!/")
                    jarRoot?.let {
                        libraryModel.addRoot(it, OrderRootType.CLASSES)
                    }
                } catch (e: Exception) {
                    // 记录但不中断，继续处理其他jar文件
                    println("警告：无法添加jar文件 ${vf.name}: ${e.message}")
                }
            }

            libraryModel.commit()

            // 将该项目库加入到所有模块依赖
            addLibraryToAllModules(project, library)

        } catch (e: Exception) {
            libraryModel.dispose()
            throw e
        }
    }

    /**
     * 从所有模块中移除指定名称的库
     */
    private fun removeLibraryFromAllModules(project: Project, libraryName: String) {
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleModel = ModuleRootManager.getInstance(module).modifiableModel
            try {
                val toRemove = moduleModel.orderEntries
                    .filterIsInstance<LibraryOrderEntry>()
                    .filter { it.library?.name == libraryName }

                toRemove.forEach { moduleModel.removeOrderEntry(it) }
                moduleModel.commit()
            } catch (e: Exception) {
                moduleModel.dispose()
                // 继续执行，不中断整个流程
            }
        }
    }

    /**
     * 将库添加到所有模块
     */
    private fun addLibraryToAllModules(project: Project, library: Library) {
        ModuleManager.getInstance(project).modules.forEach { module ->
            val moduleModel = ModuleRootManager.getInstance(module).modifiableModel
            try {
                // 检查是否已存在
                val exists = moduleModel.orderEntries
                    .filterIsInstance<LibraryOrderEntry>()
                    .any { it.library?.name == "all-in-one" }

                if (!exists) {
                    moduleModel.addLibraryEntry(library)
                }
                moduleModel.commit()
            } catch (e: Exception) {
                moduleModel.dispose()
                throw RuntimeException("无法将库添加到模块 ${module.name}: ${e.message}")
            }
        }
    }

    private fun showInfo(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "提示")
        }
    }

    private fun showError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "错误")
        }
    }

    private fun showSuccess(project: Project, count: Int) {
        ApplicationManager.getApplication().invokeLater {
            val versionInfo = if (isNewThreadingModel) "2025.1+" else "2024.3-"
            Messages.showInfoMessage(
                project,
                "成功处理了 $count 个jar文件\n所有jar包依赖已添加为库\n(兼容模式: $versionInfo)",
                "操作完成"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}