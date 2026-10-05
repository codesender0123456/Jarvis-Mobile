package com.example.actions.impl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import com.example.actions.Action
import com.example.actions.ActionResult
import com.example.actions.ParamDefinition
import com.example.actions.SafetyGuard

class MakePhoneCallAction(
    private val context: Context,
    private val safetyGuard: SafetyGuard
) : Action {

    override val name: String = "make_phone_call"
    override val description: String = "Initiates a phone call or opens the dialer for a given contact or phone number. Requires user confirmation."
    override val isSensitive: Boolean = true

    override val parameters: Map<String, ParamDefinition> = mapOf(
        "phone_number" to ParamDefinition("string", "Phone number or contact name", required = true),
        "direct_call" to ParamDefinition("boolean", "If true, requests direct dialing; otherwise opens dialer", required = false)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val phoneNumber = (args["phone_number"] as? String)?.trim()
            ?: return ActionResult("No phone number specified, Sir.", isError = true)
        val directCall = args["direct_call"] as? Boolean ?: false

        if (directCall) {
            val prompt = safetyGuard.createConfirmationRequest(
                actionName = name,
                title = "Phone Call",
                details = "Place a direct phone call to $phoneNumber?",
                onApproved = {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    try {
                        context.startActivity(intent)
                        "Call placed to $phoneNumber, Sir."
                    } catch (e: Exception) {
                        // Fallback to dialer if direct call permission not granted
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(dialIntent)
                        "Dialer opened for $phoneNumber, Sir."
                    }
                }
            )
            return ActionResult(spokenResult = prompt)
        } else {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ActionResult("Opening dialer with $phoneNumber, Sir.")
        }
    }
}

class SendSmsAction(
    private val context: Context,
    private val safetyGuard: SafetyGuard
) : Action {

    override val name: String = "send_sms"
    override val description: String = "Composes or sends an SMS text message to a phone number or contact."
    override val isSensitive: Boolean = true

    override val parameters: Map<String, ParamDefinition> = mapOf(
        "recipient" to ParamDefinition("string", "Phone number or recipient name", required = true),
        "message" to ParamDefinition("string", "Body of the text message", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val recipient = (args["recipient"] as? String)?.trim() ?: return ActionResult("Recipient missing.", isError = true)
        val message = (args["message"] as? String)?.trim() ?: return ActionResult("Message body missing.", isError = true)

        val prompt = safetyGuard.createConfirmationRequest(
            actionName = name,
            title = "Send SMS",
            details = "Send to $recipient: \"$message\"",
            onApproved = {
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$recipient")
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "SMS prepared for $recipient, Sir."
            }
        )
        return ActionResult(spokenResult = prompt)
    }
}

class SendMessagingAppAction(private val context: Context) : Action {

    override val name: String = "compose_message"
    override val description: String = "Prepares and opens message composition in WhatsApp, Telegram, or Email via deep links."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "platform" to ParamDefinition("string", "'whatsapp', 'telegram', or 'email'", required = true, enumValues = listOf("whatsapp", "telegram", "email")),
        "recipient" to ParamDefinition("string", "Phone number with country code, username, or email address", required = false),
        "text" to ParamDefinition("string", "Text or body to compose", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val platform = (args["platform"] as? String)?.lowercase() ?: "whatsapp"
        val recipient = (args["recipient"] as? String)?.trim() ?: ""
        val text = (args["text"] as? String)?.trim() ?: ""

        val intent = when (platform) {
            "whatsapp" -> {
                val cleanNumber = recipient.replace("+", "").replace(" ", "").replace("-", "")
                val uri = if (cleanNumber.isNotEmpty()) {
                    Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${Uri.encode(text)}")
                } else {
                    Uri.parse("https://api.whatsapp.com/send?text=${Uri.encode(text)}")
                }
                Intent(Intent.ACTION_VIEW, uri).apply {
                    `package` = "com.whatsapp"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "telegram" -> {
                val uri = if (recipient.isNotEmpty()) {
                    Uri.parse("https://t.me/${recipient.removePrefix("@")}?text=${Uri.encode(text)}")
                } else {
                    Uri.parse("tg://msg?text=${Uri.encode(text)}")
                }
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            "email" -> {
                Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:$recipient")
                    putExtra(Intent.EXTRA_SUBJECT, "Message via JARVIS")
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> return ActionResult("Unsupported platform $platform", isError = true)
        }

        return try {
            context.startActivity(intent)
            ActionResult("Opening $platform with your message, Sir.")
        } catch (e: Exception) {
            // If target app not installed, fallback to generic share
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Send message via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            ActionResult("$platform app not found. Opened share options, Sir.")
        }
    }
}

class ContactsLookupAction(private val context: Context) : Action {

    override val name: String = "lookup_contact"
    override val description: String = "Searches device contacts for phone numbers or details."
    override val parameters: Map<String, ParamDefinition> = mapOf(
        "query" to ParamDefinition("string", "Name or partial name of contact", required = true)
    )

    override suspend fun execute(args: Map<String, Any?>): ActionResult {
        val query = (args["query"] as? String)?.trim() ?: return ActionResult("No contact name given, Sir.", isError = true)

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        val results = mutableListOf<String>()
        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && results.size < 5) {
                    val name = it.getString(nameIndex)
                    val number = it.getString(numberIndex)
                    results.add("$name: $number")
                }
            }
        } catch (e: Exception) {
            return ActionResult("Contacts access denied or unavailable: ${e.message}", isError = true)
        }

        return if (results.isNotEmpty()) {
            ActionResult(
                spokenResult = "Found ${results.size} contact(s): ${results.joinToString(", ")}.",
                cardData = mapOf("contacts" to results)
            )
        } else {
            ActionResult("No contacts found matching '$query', Sir.")
        }
    }
}
