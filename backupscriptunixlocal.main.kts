@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.system.exitProcess



/* 
    This script is used to create a backup of server files ONLY on the local machine. 
    There are four types of backups:
    1. Creates a copy of the server files named with the date and current time and sends it to specified directory
    2. Creates a copy of the server file named with the date and current time plus a zip to the specified directory
    3. Creates a zip file named with the date and current time and sends it to the specified directory
    4. Uses rsync to sync to specified folder
*/

// Arguments to accept: 
//  1. Directory to backup
//  2. Backup directory
//  3. Backup type (full, full + zip, zip only, rsync)

fun fail(message: String, code: Int = 1): Nothing {
    println(message)
    exitProcess(code)
}

fun require(condition: Boolean, message: String, code: Int = 1) {
    if (!condition) fail(message, code)
}

fun isValidDirectory(path: Path): Boolean {
    return path.exists() && path.isDirectory()
}

fun isRsyncInstalled(): Boolean {
    val process = ProcessBuilder("sh", "-c", "command -v rsync")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    return process.waitFor() == 0
}

fun backupViaRsync(source: Path, destination: Path) {
    require(isRsyncInstalled(), "rsync is not installed or not available in PATH")

    destination.createDirectories()

    val sourcePath = "${source.toAbsolutePath().normalize()}/"
    val destinationPath = "${destination.toAbsolutePath().normalize()}/"

    println("Syncing $sourcePath -> $destinationPath")

    val process = ProcessBuilder(
        "rsync",
        "-a",
        "-v",
        sourcePath,
        destinationPath,
    )
        .inheritIO()
        .start()

    val exitCode = process.waitFor()
    require(exitCode == 0, "rsync failed with exit code $exitCode")
}

fun copyAndZip(source: Path, destination: Path) {
    val current = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("MMddyyyyHHmmss")
    val formattedDate = current.format(formatter)

    val zipPath = destination.resolve("$formattedDate.zip")
    println(zipPath.toString())

    Files.newOutputStream(zipPath).use { outputStream ->
        ZipOutputStream(outputStream).use { zipOut ->
            source.walk().forEach { file ->
                if (!file.isRegularFile()) return@forEach

                val relativePath = source.relativize(file).toString().replace('\\', '/')
                val entryName = "${source.fileName}/$relativePath"

                zipOut.putNextEntry(ZipEntry(entryName))
                Files.copy(file, zipOut)
                zipOut.closeEntry()
            }
        }
    }
}

fun copyIntoNamedSubdirectory(source: Path, destination: Path) {

    val current = LocalDateTime.now()

    val formatter = DateTimeFormatter.ofPattern("MMddyyyyHHmmss")
    val formattedDate = current.format(formatter)

    val subDirPath = Path(destination.toString(), formattedDate)

    println(subDirPath.toString())

    source.copyToRecursively(
        subDirPath,
        followLinks = false,
        overwrite = true,
    )
}
// --------------------------------------

if (args.size < 3) {
    println("Not enough parameters")
    exitProcess(1)
}

val source = Paths.get(args[0])
val destination = Paths.get(args[1])
val backupType = args[2]

val backupTypeStrings = listOf("full", "fullzip", "zip", "rsync")

require(backupType in backupTypeStrings, "Invalid type", 0)
require(isValidDirectory(source), "Not valid source")
require(isValidDirectory(destination), "Not valid destination")

when (backupType) {
    "full" -> copyIntoNamedSubdirectory(source, destination)
    "fullzip" -> {
        copyIntoNamedSubdirectory(source, destination)
        copyAndZip(source, destination)
    }
    "zip" -> copyAndZip(source, destination)
    "rsync" -> backupViaRsync(source, destination)
}