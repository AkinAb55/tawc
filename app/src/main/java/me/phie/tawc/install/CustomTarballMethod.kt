package me.phie.tawc.install

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * Метод установки кастомного дистрибутива из файла на телефоне.
 */
class CustomTarballMethod(private val context: Context) : InstallationMethod {

    override val key: String = "custom_tarball"
    override val displayName: String = "Custom tarball from storage"
    override val requiresRoot: Boolean = false

    override fun isAvailable(context: Context): Boolean = true

    /**
     * Установка из выбранного файла
     */
    fun installFromUri(uri: Uri, distroLabel: String, onProgress: (String) -> Unit): Boolean {
        val installer = TawcInstaller(context)
        val rootfsDir = installer.createRootfsDir(distroLabel)

        onProgress("Копирование файла в кэш...")

        try {
            val tempFile = File.createTempFile("custom", ".tar", context.cacheDir)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            onProgress("Распаковка rootfs...")

            ProotArchiveExtractor.extract(
                tarball = tempFile,
                rootfs = rootfsDir.absolutePath,
                stripPrefix = null,
                onLine = onProgress
            )

            tempFile.delete()

            onProgress("✅ Кастомный дистрибутив установлен!")
            return true

        } catch (e: Exception) {
            onProgress("❌ Ошибка: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // Заглушки
    override fun runOutside(script: String, onLine: ((String) -> Unit)?): MethodResult = 
        MethodResult(false, "Custom method")

    override fun startInside(rootfs: String, command: String?, graphics: GraphicsBackend?): Process = 
        throw UnsupportedOperationException()

    override fun extractBootstrap(tarball: File, rootfs: String, format: BootstrapFormat, stripPrefix: String?, tempFifo: File, onLine: (String) -> Unit) {}
}
