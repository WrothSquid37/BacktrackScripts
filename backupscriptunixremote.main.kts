@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.system.exitProcess

/*
    This script is used to create a backup of server files ONLY on the remote machine.
    There are four types of backups:
    1. Creates a copy of the server files named with the date and current time and sends it to specified directory
    2. Creates a copy of the server file named with the date and current time plus a zip to the specified directory
    3. Creates a zip file named with the date and current time and sends it to the specified directory
    4. Uses rsync to sync to specified folder
*/

// Arguments to accept:
//  1. Source to backup from current machine (that hosts API server with the files to backup)
//  2. Destination to backup to on remote machine (user@host:/path)
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

fun isCommandInstalled(command: String): Boolean {
    val process = ProcessBuilder("sh", "-c", "command -v $command")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    return process.waitFor() == 0
}

fun requireCommandInstalled(command: String) {
    require(isCommandInstalled(command), "$command is not installed or not available in PATH")
}

fun runProcess(vararg command: String): Int {
    val process = ProcessBuilder(*command)
        .inheritIO()
        .start()
    return process.waitFor()
}

fun parseRemoteDestination(remote: String): Pair<String, String> {
    val colonIndex = remote.indexOf(':')
    require(colonIndex > 0, "Remote destination must be in the form user@host:/path")
    val host = remote.substring(0, colonIndex)
    val path = remote.substring(colonIndex + 1)
    require(path.isNotEmpty(), "Remote path cannot be empty")
    return host to path
}

fun isValidRemoteDirectory(host: String, path: String): Boolean {
    requireCommandInstalled("ssh")
    val process = ProcessBuilder("ssh", host, "test", "-d", path)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    return process.waitFor() == 0
}

fun sshRun(host: String, remoteCommand: String) {
    requireCommandInstalled("ssh")
    val exitCode = runProcess("ssh", host, remoteCommand)
    require(exitCode == 0, "ssh command failed with exit code $exitCode")
}

fun rsyncToRemote(source: String, remoteTarget: String) {
    requireCommandInstalled("rsync")
    val exitCode = runProcess(
        "rsync",
        "-a",
        "-v",
        "-e",
        "ssh",
        source,
        remoteTarget,
    )
    require(exitCode == 0, "rsync failed with exit code $exitCode")
}

fun formattedTimestamp(): String {
    val current = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("MMddyyyyHHmmss")
    return current.format(formatter)
}

fun createZipFile(source: Path, zipPath: Path) {
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

fun copyIntoNamedSubdirectoryRemote(source: Path, host: String, remoteBasePath: String) {
    val formattedDate = formattedTimestamp()
    val remoteDestPath = "$remoteBasePath/$formattedDate"

    sshRun(host, "mkdir -p '$remoteDestPath'")

    val sourcePath = "${source.toAbsolutePath().normalize()}/"
    val remoteTarget = "$host:$remoteDestPath/"

    println("$host:$remoteDestPath")
    rsyncToRemote(sourcePath, remoteTarget)
}

fun copyAndZipRemote(source: Path, host: String, remoteBasePath: String) {
    val formattedDate = formattedTimestamp()
    val remoteZipPath = "$remoteBasePath/$formattedDate.zip"
    val tempZip = Files.createTempFile("backup", ".zip")

    try {
        createZipFile(source, tempZip)
        println("$host:$remoteZipPath")
        rsyncToRemote(tempZip.toAbsolutePath().toString(), "$host:$remoteZipPath")
    } finally {
        Files.deleteIfExists(tempZip)
    }
}

fun backupViaRsyncRemote(source: Path, host: String, remotePath: String) {
    requireCommandInstalled("rsync")

    sshRun(host, "mkdir -p '$remotePath'")

    val sourcePath = "${source.toAbsolutePath().normalize()}/"
    val remoteTarget = "$host:$remotePath/"

    println("Syncing $sourcePath -> $remoteTarget")
    rsyncToRemote(sourcePath, remoteTarget)
}

// --------------------------------------

if (args.size < 3) {
    println("Not enough parameters")
    exitProcess(1)
}

val source = Paths.get(args[0])
val (remoteHost, remotePath) = parseRemoteDestination(args[1])
val backupType = args[2]

val backupTypeStrings = listOf("full", "fullzip", "zip", "rsync")

require(backupType in backupTypeStrings, "Invalid type", 0)
require(isValidDirectory(source), "Not valid source")
require(isValidRemoteDirectory(remoteHost, remotePath), "Not valid destination")

when (backupType) {
    "full" -> copyIntoNamedSubdirectoryRemote(source, remoteHost, remotePath)
    "fullzip" -> {
        copyIntoNamedSubdirectoryRemote(source, remoteHost, remotePath)
        copyAndZipRemote(source, remoteHost, remotePath)
    }
    "zip" -> copyAndZipRemote(source, remoteHost, remotePath)
    "rsync" -> backupViaRsyncRemote(source, remoteHost, remotePath)
}
