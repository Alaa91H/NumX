package com.numx.app

/**
 * The device's default destination for new contacts.
 *
 * Android 16 introduced a restriction: when the user's default contacts account
 * is a cloud (or SIM) account, the contacts provider rejects rows explicitly
 * targeting local storage. The app therefore has to know the default and either
 * use it (with the user's consent) or ask the user to switch to device storage.
 */
enum class ContactStorageKind {
    LOCAL,
    CLOUD,
    SIM,
    NOT_SET,
    UNKNOWN,
}

data class ContactAccount(
    val type: String,
    val name: String,
)

class ContactStorageException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

data class ContactStorageInfo(
    val kind: ContactStorageKind = ContactStorageKind.UNKNOWN,
    val accountName: String? = null,
    val accountType: String? = null,
) {
    val isCloudOrSim: Boolean
        get() = kind == ContactStorageKind.CLOUD || kind == ContactStorageKind.SIM

    val canUseCloudAccount: Boolean
        get() = isCloudOrSim &&
            !accountType.isNullOrBlank() &&
            !accountName.isNullOrBlank()

    val displayLabel: String
        get() = when (kind) {
            ContactStorageKind.CLOUD -> when (accountType) {
                "com.google" -> "Google (سحابي)"
                else -> "حساب سحابي"
            }
            ContactStorageKind.SIM -> "شريحة SIM"
            ContactStorageKind.LOCAL,
            ContactStorageKind.NOT_SET,
            -> "ذاكرة الجهاز (محلي)"
            ContactStorageKind.UNKNOWN -> "غير معروف"
        }

    /**
     * The explicit cloud/SIM account row to write to, or null for local
     * storage. Local inserts write null account columns explicitly; the
     * provider treats those as local storage.
     */
    fun asAccountOrNull(): ContactAccount? {
        if (!isCloudOrSim) return null
        val type = accountType?.takeIf { it.isNotBlank() } ?: return null
        val name = accountName?.takeIf { it.isNotBlank() } ?: return null
        return ContactAccount(type, name)
    }

    val choiceRequiredMessage: String
        get() = "مكان الحفظ الافتراضي هو $displayLabel. " +
            "يمنع Android 16+ إنشاء جهات اتصال محلية عندما يكون الافتراضي سحابياً. " +
            "غيّر الافتراضي إلى «تخزين الجهاز» من إعدادات جهات الاتصال، " +
            "أو اضغط «الحفظ في الحساب الحالي» للمتابعة."

    companion object {
        val LOCAL = ContactStorageInfo(ContactStorageKind.LOCAL)
        val UNKNOWN = ContactStorageInfo(ContactStorageKind.UNKNOWN)
    }
}
