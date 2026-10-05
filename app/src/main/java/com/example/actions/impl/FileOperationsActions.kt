package com.example.actions.impl

import android.content.Context
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.actions.SafetyGuard
import com.example.actions.UndoStack
import com.example.actions.UndoableAction
import com.example.core.gemini.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileOperationsActions(
    private val context: Context,
    private val geminiClient: GeminiClient,
    private val undoStack: UndoStack,
    private val safetyGuard: SafetyGuard
) : Action {

    override val name: String = "file_operations"
    override val description: String = "Performs file operations in app storage: read/summarise, create, rename, or delete."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "operation" to ParamDefinition(
            "string",
            "'read', 'summarise', 'create', 'rename', or 'delete'",
            required = true,
            enumValues = listOf("read", "summarise", "create", "rename", "delete")
        ),
        "file_name" to ParamDefinition("string", "File name (e.g. notes.txt)", required = true),
        "content" to ParamDefinition("string", "Content for create operation", required = false),
        "new_name" to ParamDefinition("string", "New name for rename operation", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult = withContext(Dispatchers.IO) {
        val operation = (args["operation"] as? String)?.lowercase() ?: "read"
        val fileName = (args["file_name"] as? String)?.trim() ?: return@withContext ActionResult("File name required.", isError = true)
        val appFilesDir = context.getExternalFilesDir(null) ?: context.filesDir

        val targetFile = resolveInside(appFilesDir, fileName)
            ?: return@withContext ActionResult(
                "File name '$fileName' is outside the JARVIS workspace, Sir.",
                isError = true
            )

        when (operation) {
            "read", "summarise" -> {
                if (!targetFile.exists()) {
                    return@withContext ActionResult("File '$fileName' not found in workspace, Sir.", isError = true)
                }
                val text = try {
                    if (targetFile.length() > MAX_READABLE_BYTES) {
                        return@withContext ActionResult(
                            "File '$fileName' is too large to read (${targetFile.length()} bytes).",
                            isError = true
                        )
                    }
                    targetFile.readText()
                } catch (e: Exception) {
                    return@withContext ActionResult("Unable to read file: ${e.message}", isError = true)
                }

                if (operation == "summarise") {
                    val prompt = "Provide a 2-sentence summary of this document:\n\n${text.take(3000)}"
                    val summary = geminiClient.callOneShot(prompt)
                    if (summary.isError) {
                        ActionResult("Could not summarise $fileName: ${summary.text}", isError = true)
                    } else {
                        ActionResult("Summary of $fileName: ${summary.text}")
                    }
                } else {
                    ActionResult("Read ${text.length} characters from $fileName. Preview: ${text.take(200)}")
                }
            }

            "create" -> {
                val content = (args["content"] as? String) ?: ""
                if (targetFile.exists()) {
                    // Overwriting then pushing a delete-undo would destroy pre-existing data.
                    return@withContext ActionResult(
                        "File '$fileName' already exists. Delete or rename it first, Sir.",
                        isError = true
                    )
                }
                try {
                    targetFile.writeText(content)
                    undoStack.push(
                        UndoableAction("Delete newly created file $fileName") {
                            if (targetFile.exists()) targetFile.delete()
                            "Reverted creation: deleted $fileName."
                        }
                    )
                    ActionResult("Created file $fileName with ${content.length} characters, Sir.")
                } catch (e: Exception) {
                    ActionResult("Failed to create file: ${e.message}", isError = true)
                }
            }

            "rename" -> {
                val newName = (args["new_name"] as? String)?.trim()
                    ?: return@withContext ActionResult("New file name required.", isError = true)
                val newFile = resolveInside(appFilesDir, newName)
                    ?: return@withContext ActionResult(
                        "File name '$newName' is outside the JARVIS workspace, Sir.",
                        isError = true
                    )
                if (!targetFile.exists()) {
                    return@withContext ActionResult("Source file $fileName does not exist.", isError = true)
                }
                if (newFile.exists()) {
                    return@withContext ActionResult("A file named $newName already exists.", isError = true)
                }

                if (!targetFile.renameTo(newFile)) {
                    return@withContext ActionResult("Could not rename $fileName to $newName.", isError = true)
                }
                undoStack.push(
                    UndoableAction("Rename $newName back to $fileName") {
                        if (newFile.exists()) newFile.renameTo(targetFile)
                        "Reverted name: restored $fileName."
                    }
                )
                ActionResult("Renamed $fileName to $newName, Sir.")
            }

            "delete" -> {
                if (!targetFile.exists()) {
                    return@withContext ActionResult("File '$fileName' does not exist.", isError = true)
                }
                // Sensitive: delete requires safety tap confirmation
                val prompt = safetyGuard.createConfirmationRequest(
                    actionName = name,
                    title = "Delete File",
                    details = "Permanently remove '$fileName'?",
                    onApproved = {
                        if (targetFile.exists() && targetFile.delete()) {
                            "File $fileName was deleted, Sir."
                        } else {
                            "File $fileName could not be deleted."
                        }
                    }
                )
                ActionResult(spokenResult = prompt)
            }

            else -> ActionResult("Unrecognized file operation: $operation", isError = true)
        }
    }

    /**
     * Joins [name] to [workspace] and rejects anything that escapes it. The model supplies these
     * names, so a plain File(parent, child) allows "../.." traversal of app storage.
     * Returns null when the resolved path is outside the workspace directory.
     */
private fun resolveInside(workspace: File, name: String): File? {
        if (name.isBlank()) return null
        val root = workspace.canonicalFile
        val candidate = File(root, name).canonicalFile
        return if (candidate == root || candidate.path.startsWith(root.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    private companion object {
        const val MAX_READABLE_BYTES = 2L * 1024 * 1024
    }
}
