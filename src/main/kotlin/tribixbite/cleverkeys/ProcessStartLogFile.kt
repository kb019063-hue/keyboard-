package tribixbite.cleverkeys

import android.content.Context
import java.io.File

/**
 * Where [ProcessStartLog] keeps its list. Device-protected storage, so that a start before the
 * first unlock after a reboot is counted like any other (the keyboard is direct-boot aware);
 * the no-backup directory, because another phone's start times mean nothing on this one.
 *
 * Touches the file system (the directory is created on first use): call off the main thread on
 * the keyboard's own paths.
 */
internal fun processStartLogFile(context: Context): File =
    File(context.createDeviceProtectedStorageContext().noBackupFilesDir, "keyboard-starts.txt")
