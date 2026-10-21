package com.qshlab.daybreak.desktop

import com.qshlab.daybreak.Daybreak
import com.qshlab.daybreak.data.writeJson
import com.qshlab.daybreak.sources.Mail
import com.qshlab.daybreak.sources.MailBox
import com.qshlab.daybreak.sources.SharedMail
import java.io.File
import java.time.Instant

/** Development only: `./gradlew -q :desktopApp:mailmergecheck` — whole emails replace notification previews of the same email. */
fun main() {
    Daybreak.start(MacPlatform())
    val now = System.currentTimeMillis(); val h = 3600_000L
    val dir = File(Daybreak.platform.home, "mail").apply { deleteRecursively(); mkdirs() }
    File(dir, "applemail-mac.json").writeJson(MailBox("applemail", "mac", "MacBook", now, true, listOf(
        Mail("NUS", "Prof Tan <tan@nus.edu.sg>", "Re: Draft chapter 3", now - 2 * h, "Looks good, two comments…"),
        Mail("Gmail", "Mum <mum@gmail.com>", "Dinner Sunday?", now - 5 * h, "Are you coming…"),
    )))
    File(dir, "notifications-phone.json").writeJson(MailBox("notifications", "phone", "Pixel", now, false, listOf(
        Mail("Outlook", "Prof Tan", "RE: Draft chapter 3", now - 2 * h + 40_000, "Looks good, two"),          // same as Apple Mail's
        Mail("Outlook", "IT Helpdesk", "Password expiry", now - 1 * h, "Your password expires in"),          // only seen on the phone
        Mail("Gmail", "Mum", "Dinner Sunday?", now - 4 * h, "Are you coming"),                               // same, an hour later
        Mail("Gmail", "Old", "Last week", now - 100 * h, "too old"),                                          // before `since`
    )))
    val fromAccount = listOf(Mail("me@gmail.com", "Mum <mum@gmail.com>", "Dinner Sunday?", now - 5 * h + 30_000, "Are you coming…"))  // also via IMAP
    val merged = SharedMail.merge(fromAccount, Instant.ofEpochMilli(now - 72 * h))
    merged.forEach { println("${it.account.padEnd(14)} ${it.from.padEnd(28)} ${it.subject}") }
    val ok = merged.size == 3 && merged.count { it.subject.contains("chapter", true) } == 1 && merged.count { it.subject == "Dinner Sunday?" } == 1 &&
        merged.any { it.subject == "Password expiry" } && merged.none { it.subject == "Last week" } && merged.first { it.subject.contains("chapter", true) }.account == "NUS"
    println(if (ok) "MERGE OK" else "MERGE WRONG")
}
