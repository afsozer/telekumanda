package com.agent.bridge

/**
 * Returns a breadcrumb trail for the given absolute path.
 * Each entry is a Pair(name, absolutePath). The first entry is always the root.
 *
 * Examples:
 *   "C:\\Users\\Dev\\agtest" → [("C:", "C:"), ("Users", "C:\\Users"), ("Dev", "C:\\Users\\Dev"), ("agtest", "C:\\Users\\Dev\\agtest")]
 *   "/home/user/dir"           → [("/", "/"), ("home", "/home"), ("user", "/home/user"), ("dir", "/home/user/dir")]
 */
fun folderBreadcrumb(input: String): List<Pair<String, String>> {
    val clean = input.trim()
    if (clean.isBlank()) return emptyList()

    val result = mutableListOf<Pair<String, String>>()

    // Windows paths: C:\..., D:\...
    val driveMatch = Regex("^([A-Za-z]):[\\\\/]?(.*)$").find(clean)
    if (driveMatch != null) {
        val drive = driveMatch.groupValues[1].uppercase() + ":"
        val rest = driveMatch.groupValues[2].trim('\\', '/')
        result.add(drive to drive)
        var current = drive
        if (rest.isNotBlank()) {
            for (part in rest.split(Regex("[\\\\/]")).filter { it.isNotBlank() }) {
                current = "$current\\$part"
                result.add(part to current)
            }
        }
        return result
    }

    // UNC paths: \\server\share\...
    if (clean.startsWith("\\\\")) {
        val parts = clean.substring(2).split(Regex("[\\\\/]")).filter { it.isNotBlank() }
        if (parts.size >= 2) {
            val shareRoot = "\\\\${parts[0]}\\${parts[1]}"
            result.add("${parts[0]}\\${parts[1]}" to shareRoot)
            var current = shareRoot
            for (i in 2 until parts.size) {
                current = "$current\\${parts[i]}"
                result.add(parts[i] to current)
            }
        } else {
            result.add(clean to clean)
        }
        return result
    }

    // Unix/linux paths: /home/user/dir
    if (clean.startsWith("/")) {
        val parts = clean.split('/').filter { it.isNotBlank() }
        result.add("/" to "/")
        var current = ""
        for (part in parts) {
            current = "$current/$part"
            result.add(part to current)
        }
        return result
    }

    // Fallback: single segment
    result.add(clean to clean)
    return result
}

/** Normalize a path for comparison: backslash → forward slash, trim, lowercase. */
fun folderPathKey(path: String): String = path
    .trim()
    .replace('\\', '/')
    .trimEnd('/')
    .lowercase()
